package org.asamk.signal.manager.storage.sendLog;

import org.asamk.signal.manager.groups.GroupId;
import org.asamk.signal.manager.groups.GroupUtils;
import org.asamk.signal.manager.storage.Database;
import org.asamk.signal.manager.storage.recipients.RecipientId;
import org.asamk.signal.manager.storage.recipients.RecipientResolver;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class MessageSendLogStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MessageSendLogStore.class);

    private static final String TABLE_MESSAGE_SEND_LOG = "message_send_log";
    private static final String TABLE_MESSAGE_SEND_LOG_CONTENT = "message_send_log_content";

    private static final Duration LOG_DURATION = Duration.ofDays(1);

    private final RecipientResolver recipientResolver;
    private final Database database;
    private final Thread cleanupThread;

    public MessageSendLogStore(
            final RecipientResolver recipientResolver, final Database database
    ) {
        this.recipientResolver = recipientResolver;
        this.database = database;
        this.cleanupThread = new Thread(() -> {
            try {
                final var interval = Duration.ofHours(1).toMillis();
                while (!Thread.interrupted()) {
                    try (final var connection = database.getConnection()) {
                        deleteOutdatedEntries(connection);
                    } catch (SQLException e) {
                        logger.warn("Deleting outdated entries failed");
                        break;
                    }
                    Thread.sleep(interval);
                }
            } catch (InterruptedException e) {
                logger.debug("Stopping msl cleanup thread");
            }
        });
        cleanupThread.setName("msl-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    public static void createSql(Connection connection) throws SQLException {
        try (final var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE message_send_log (
                      _id INTEGER PRIMARY KEY,
                      content_id INTEGER NOT NULL REFERENCES message_send_log_content (_id) ON DELETE CASCADE,
                      recipient_id INTEGER NOT NULL,
                      device_id INTEGER NOT NULL
                    );
                    CREATE TABLE message_send_log_content (
                      _id INTEGER PRIMARY KEY,
                      group_id BLOB,
                      timestamp INTEGER NOT NULL,
                      content BLOB NOT NULL,
                      content_hint INTEGER NOT NULL
                    );
                    CREATE INDEX mslc_timestamp_index ON message_send_log_content (timestamp);
                    CREATE INDEX msl_recipient_index ON message_send_log (recipient_id, device_id, content_id);
                    CREATE INDEX msl_content_index ON message_send_log (content_id);
                    """);
        }
    }

    public List<MessageSendLogEntry> findMessages(
            final RecipientId recipientId, final int deviceId, final long timestamp, final boolean isSenderKey
    ) {
        try (final var connection = database.getConnection()) {
            deleteOutdatedEntries(connection);

            try (final var statement = connection.prepareStatement(
                    "SELECT group_id, content, content_hint FROM %s l INNER JOIN %s lc ON l.content_id = lc._id WHERE l.recipient_id = ? AND l.device_id = ? AND lc.timestamp = ?".formatted(
                            TABLE_MESSAGE_SEND_LOG,
                            TABLE_MESSAGE_SEND_LOG_CONTENT))) {
                statement.setLong(1, recipientId.id());
                statement.setInt(2, deviceId);
                statement.setLong(3, timestamp);
                try (var result = executeQueryForStream(statement, resultSet -> {
                    final var groupId = Optional.ofNullable(resultSet.getBytes("group_id"))
                            .map(GroupId::unknownVersion);
                    final SignalServiceProtos.Content content;
                    try {
                        content = SignalServiceProtos.Content.parseFrom(resultSet.getBinaryStream("content"));
                    } catch (IOException e) {
                        logger.warn("Failed to parse content from message send log", e);
                        return null;
                    }
                    final var contentHint = ContentHint.fromType(resultSet.getInt("content_hint"));
                    return new MessageSendLogEntry(groupId, content, contentHint);
                })) {
                    return result.filter(Objects::nonNull)
                            .filter(e -> !isSenderKey || e.groupId().isPresent())
                            .toList();
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed read from message send log", e);
            return List.of();
        }
    }

    public long insertIfPossible(
            long sentTimestamp, SendMessageResult sendMessageResult, ContentHint contentHint
    ) {
        final RecipientDevices recipientDevice = getRecipientDevices(sendMessageResult);
        if (recipientDevice == null) {
            return -1;
        }

        return insert(List.of(recipientDevice),
                sentTimestamp,
                sendMessageResult.getSuccess().getContent().get(),
                contentHint);
    }

    public long insertIfPossible(
            long sentTimestamp, List<SendMessageResult> sendMessageResults, ContentHint contentHint
    ) {
        final var recipientDevices = sendMessageResults.stream()
                .map(this::getRecipientDevices)
                .filter(Objects::nonNull)
                .toList();
        if (recipientDevices.isEmpty()) {
            return -1;
        }

        final var content = sendMessageResults.stream()
                .filter(r -> r.isSuccess() && r.getSuccess().getContent().isPresent())
                .map(r -> r.getSuccess().getContent().get())
                .findFirst()
                .get();

        return insert(recipientDevices, sentTimestamp, content, contentHint);
    }

    public void addRecipientToExistingEntryIfPossible(final long contentId, final SendMessageResult sendMessageResult) {
        final RecipientDevices recipientDevice = getRecipientDevices(sendMessageResult);
        if (recipientDevice == null) {
            return;
        }

        insertRecipientsForExistingContent(contentId, List.of(recipientDevice));
    }

    public void addRecipientToExistingEntryIfPossible(
            final long contentId, final List<SendMessageResult> sendMessageResults
    ) {
        final var recipientDevices = sendMessageResults.stream()
                .map(this::getRecipientDevices)
                .filter(Objects::nonNull)
                .toList();
        if (recipientDevices.isEmpty()) {
            return;
        }

        insertRecipientsForExistingContent(contentId, recipientDevices);
    }

    public void deleteEntryForGroup(long sentTimestamp, GroupId groupId) {
        try (final var connection = database.getConnection()) {
            try (final var statement = connection.prepareStatement(
                    "DELETE FROM %s AS lc WHERE lc.timestamp = ? AND lc.group_id = ?".formatted(
                            TABLE_MESSAGE_SEND_LOG_CONTENT))) {
                statement.setLong(1, sentTimestamp);
                statement.setBytes(2, groupId.serialize());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warn("Failed delete from message send log", e);
        }
    }

    public void deleteEntryForRecipientNonGroup(long sentTimestamp, RecipientId recipientId) {
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (final var statement = connection.prepareStatement(
                    "DELETE FROM %s AS lc WHERE lc.timestamp = ? AND lc.group_id IS NULL AND lc._id IN (SELECT content_id FROM %s l WHERE l.recipient_id = ?)".formatted(
                            TABLE_MESSAGE_SEND_LOG_CONTENT,
                            TABLE_MESSAGE_SEND_LOG))) {
                statement.setLong(1, sentTimestamp);
                statement.setLong(2, recipientId.id());
                statement.executeUpdate();
            }

            deleteOrphanedLogContents(connection);
            connection.commit();
        } catch (SQLException e) {
            logger.warn("Failed delete from message send log", e);
        }
    }

    public void deleteEntryForRecipient(long sentTimestamp, RecipientId recipientId, int deviceId) {
        deleteEntriesForRecipient(List.of(sentTimestamp), recipientId, deviceId);
    }

    public void deleteEntriesForRecipient(List<Long> sentTimestamps, RecipientId recipientId, int deviceId) {
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (final var statement = connection.prepareStatement(
                    "DELETE FROM %s AS l WHERE l.content_id IN (SELECT _id FROM %s lc WHERE lc.timestamp = ?) AND l.recipient_id = ? AND l.device_id = ?".formatted(
                            TABLE_MESSAGE_SEND_LOG,
                            TABLE_MESSAGE_SEND_LOG_CONTENT))) {
                for (final var sentTimestamp : sentTimestamps) {
                    statement.setLong(1, sentTimestamp);
                    statement.setLong(2, recipientId.id());
                    statement.setInt(3, deviceId);
                    statement.executeUpdate();
                }
            }

            deleteOrphanedLogContents(connection);
            connection.commit();
        } catch (SQLException e) {
            logger.warn("Failed delete from message send log", e);
        }
    }

    @Override
    public void close() {
        cleanupThread.interrupt();
        try {
            cleanupThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    private RecipientDevices getRecipientDevices(final SendMessageResult sendMessageResult) {
        if (sendMessageResult.isSuccess() && sendMessageResult.getSuccess().getContent().isPresent()) {
            final var recipientId = recipientResolver.resolveRecipient(sendMessageResult.getAddress());
            return new RecipientDevices(recipientId, sendMessageResult.getSuccess().getDevices());
        } else {
            return null;
        }
    }

    private long insert(
            final List<RecipientDevices> recipientDevices,
            final long sentTimestamp,
            final SignalServiceProtos.Content content,
            final ContentHint contentHint
    ) {
        byte[] groupId = getGroupId(content);

        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            final long contentId;
            try (final var statement = connection.prepareStatement(
                    "INSERT INTO %s (timestamp, group_id, content, content_hint) VALUES (?,?,?,?)".formatted(
                            TABLE_MESSAGE_SEND_LOG_CONTENT))) {
                statement.setLong(1, sentTimestamp);
                statement.setBytes(2, groupId);
                statement.setBytes(3, content.toByteArray());
                statement.setInt(4, contentHint.getType());
                statement.executeUpdate();
                final var generatedKeys = statement.getGeneratedKeys();
                if (generatedKeys.next()) {
                    contentId = generatedKeys.getLong(1);
                } else {
                    contentId = -1;
                }
            }
            if (contentId == -1) {
                logger.warn("Failed to insert message send log content");
                return -1;
            }
            insertRecipientsForExistingContent(contentId, recipientDevices, connection);

            connection.commit();
            return contentId;
        } catch (SQLException e) {
            logger.warn("Failed to insert into message send log", e);
            return -1;
        }
    }

    private byte[] getGroupId(final SignalServiceProtos.Content content) {
        try {
            return !content.hasDataMessage()
                    ? null
                    : content.getDataMessage().hasGroup()
                            ? content.getDataMessage().getGroup().getId().toByteArray()
                            : content.getDataMessage().hasGroupV2()
                                    ? GroupUtils.getGroupIdV2(new GroupMasterKey(content.getDataMessage()
                                    .getGroupV2()
                                    .getMasterKey()
                                    .toByteArray())).serialize()
                                    : null;
        } catch (InvalidInputException e) {
            logger.warn("Failed to parse groupId id from content");
            return null;
        }
    }

    private void insertRecipientsForExistingContent(
            final long contentId, final List<RecipientDevices> recipientDevices
    ) {
        try (final var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            insertRecipientsForExistingContent(contentId, recipientDevices, connection);
            connection.commit();
        } catch (SQLException e) {
            logger.warn("Failed to append recipients to message send log", e);
        }
    }

    private void insertRecipientsForExistingContent(
            final long contentId, final List<RecipientDevices> recipientDevices, final Connection connection
    ) throws SQLException {
        try (final var statement = connection.prepareStatement(
                "INSERT INTO %s (recipient_id, device_id, content_id) VALUES (?,?,?)".formatted(TABLE_MESSAGE_SEND_LOG))) {
            for (final var recipientDevice : recipientDevices) {
                for (final var deviceId : recipientDevice.deviceIds()) {
                    statement.setLong(1, recipientDevice.recipientId().id());
                    statement.setInt(2, deviceId);
                    statement.setLong(3, contentId);
                    statement.executeUpdate();
                }
            }
        }
    }

    private void deleteOutdatedEntries(final Connection connection) throws SQLException {
        try (final var statement = connection.prepareStatement("DELETE FROM %s WHERE timestamp < ?".formatted(
                TABLE_MESSAGE_SEND_LOG_CONTENT))) {
            statement.setLong(1, System.currentTimeMillis() - LOG_DURATION.toMillis());
            final var rowCount = statement.executeUpdate();
            if (rowCount > 0) {
                logger.debug("Removed {} outdated entries from the message send log", rowCount);
            } else {
                logger.trace("No outdated entries to be removed from message send log.");
            }
        }
    }

    private void deleteOrphanedLogContents(final Connection connection) throws SQLException {
        try (final var statement = connection.prepareStatement(
                "DELETE FROM %s WHERE _id NOT IN (SELECT content_id FROM %s)".formatted(TABLE_MESSAGE_SEND_LOG_CONTENT,
                        TABLE_MESSAGE_SEND_LOG))) {
            statement.executeUpdate();
        }
    }

    private <T> Stream<T> executeQueryForStream(
            PreparedStatement statement, ResultSetMapper<T> mapper
    ) throws SQLException {
        final var resultSet = statement.executeQuery();

        return StreamSupport.stream(new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(final Consumer<? super T> consumer) {
                try {
                    if (!resultSet.next()) {
                        return false;
                    }
                    consumer.accept(mapper.apply(resultSet));
                    return true;
                } catch (SQLException e) {
                    logger.warn("Failed to read from database result", e);
                    throw new RuntimeException(e);
                }
            }
        }, false);
    }

    private interface ResultSetMapper<T> {

        T apply(ResultSet resultSet) throws SQLException;
    }

    private record RecipientDevices(RecipientId recipientId, List<Integer> deviceIds) {}
}
