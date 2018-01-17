/*
 * Copyright 2017 Oursky Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.skygear.plugins.chat;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import io.realm.RealmQuery;
import io.skygear.skygear.Error;

import static io.skygear.plugins.chat.MessageCacheObject.KEY_ALREADY_SYNC_TO_SERVER;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_CONVERSATION_ID;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_CREATION_DATE;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_DELETED;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_EDITION_DATE;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_FAIL;
import static io.skygear.plugins.chat.MessageCacheObject.KEY_SEND_DATE;

import static io.skygear.plugins.chat.MessageSubscriptionCallback.EVENT_TYPE_CREATE;
import static io.skygear.plugins.chat.MessageSubscriptionCallback.EVENT_TYPE_DELETE;
import static io.skygear.plugins.chat.MessageSubscriptionCallback.EVENT_TYPE_UPDATE;

/**
 * The cache controller that contains the logic of updating cache store when
 * calling chat container api and receiving response.
 */
class CacheController {
    private static CacheController sharedInstance;

    static CacheController getInstance() {
        if (sharedInstance == null) {
            RealmStore store = new RealmStore("SKYChatCache", false);
            sharedInstance = new CacheController(store);
        }

        return sharedInstance;
    }

    RealmStore store;

    CacheController(RealmStore store) {
        this.store = store;
    }

    //region Message

    void getMessages(@NonNull final Conversation conversation,
                            final int limit,
                            @Nullable final Date before,
                            @Nullable final String order,
                            @Nullable final GetCallback<List<Message>> callback) {
        RealmStore.QueryBuilder<MessageCacheObject> queryBuilder = new RealmStore.QueryBuilder<MessageCacheObject>() {
            @Override
            public RealmQuery<MessageCacheObject> buildQueryFrom(RealmQuery<MessageCacheObject> baseQuery) {
                RealmQuery<MessageCacheObject> query = baseQuery
                        .equalTo(KEY_CONVERSATION_ID, conversation.getId())
                        .equalTo(KEY_DELETED, false)
                        .beginGroup()
                            .beginGroup()
                                .equalTo(KEY_ALREADY_SYNC_TO_SERVER, true)
                                .equalTo(KEY_FAIL, false)
                            .endGroup()
                            .or()
                            .isNull(KEY_SEND_DATE)
                        .endGroup();

                if (before != null) {
                    query.lessThan(KEY_CREATION_DATE, before);
                }

                return query;
            }
        };

        String resolvedOrder = order;
        if (resolvedOrder != null && resolvedOrder.equalsIgnoreCase("edited_at")) {
            resolvedOrder = KEY_EDITION_DATE;
        } else {
            resolvedOrder = KEY_CREATION_DATE;
        }

        if (callback != null) {
            this.store.getMessages(queryBuilder, limit, resolvedOrder, new RealmStore.ResultCallback<Message[]>() {
                @Override
                public void onResultGet(Message[] messages) {
                    callback.onSuccess(Arrays.asList(messages));
                }
            });
        }
    }

    void didGetMessages(Message[] messages, Message[] deletedMessages) {
        this.store.setMessages(messages);

        // soft delete
        // so update the messages
        this.store.setMessages(deletedMessages);
    }


    void saveMessage(final Message message,
                     @Nullable final SaveCallback<Message> callback) {
        this.store.setMessages(new Message[]{message});

        if (callback != null) {
            callback.onSuccess(message);
        }
    }

    void didSaveMessage(final Message message,
                        @Nullable Error error) {
        if (error != null) {
            // invalidate unsaved message
            message.alreadySyncToServer = false;
            message.fail = true;
        } else {
            message.alreadySyncToServer = true;
            message.fail = false;
        }

        this.store.setMessages(new Message[]{message});
    }

    void didDeleteMessage(final Message message) {
        // soft delete
        // so update the messages
        this.store.setMessages(new Message[]{message});
    }

    void handleMessageChange(Message message, String eventType) {
        if (eventType.equals(EVENT_TYPE_CREATE)) {
            this.didSaveMessage(message, null);
        }

        if (eventType.equals(EVENT_TYPE_UPDATE)) {
            this.didSaveMessage(message, null);
        }

        if (eventType.equals(EVENT_TYPE_DELETE)) {
            this.didDeleteMessage(message);
        }
    }

    //endregion

    //region Message Operation

    private void fetchMessageOperations(final @NonNull RealmStore.QueryBuilder<MessageOperationCacheObject> queryBuilder,
                                        final @Nullable GetCallback<List<MessageOperation>> callback) {
        this.store.getMessageOperations(queryBuilder, -1, MessageOperationCacheObject.KEY_SEND_DATE, new RealmStore.ResultCallback<MessageOperation[]>() {
            @Override
            public void onResultGet(MessageOperation[] operations) {
                if (callback != null) {
                    callback.onSuccess(Arrays.asList(operations));
                }
            }
        });
    }

    void fetchMessageOperations(final @NonNull Conversation conversation,
                                final @NonNull MessageOperation.Type operationType,
                                @Nullable GetCallback<List<MessageOperation>> callback) {
        RealmStore.QueryBuilder<MessageOperationCacheObject> queryBuilder = new RealmStore.QueryBuilder<MessageOperationCacheObject>() {
            @Override
            public RealmQuery<MessageOperationCacheObject> buildQueryFrom(RealmQuery<MessageOperationCacheObject> baseQuery) {
                RealmQuery<MessageOperationCacheObject> query = baseQuery
                        .equalTo(MessageOperationCacheObject.KEY_CONVERSATION_ID, conversation.getId())
                        .equalTo(MessageOperationCacheObject.KEY_TYPE, operationType.getName());
                return query;
            }
        };

        this.fetchMessageOperations(queryBuilder, callback);
    }

    void fetchMessageOperations(final @NonNull Message message,
                                final @NonNull MessageOperation.Type operationType,
                                @Nullable GetCallback<List<MessageOperation>> callback) {
        RealmStore.QueryBuilder<MessageOperationCacheObject> queryBuilder = new RealmStore.QueryBuilder<MessageOperationCacheObject>() {
            @Override
            public RealmQuery<MessageOperationCacheObject> buildQueryFrom(RealmQuery<MessageOperationCacheObject> baseQuery) {
                RealmQuery<MessageOperationCacheObject> query = baseQuery
                        .equalTo(MessageOperationCacheObject.KEY_MESSAGE_ID, message.getId())
                        .equalTo(MessageOperationCacheObject.KEY_TYPE, operationType.getName());
                return query;
            }
        };

        this.fetchMessageOperations(queryBuilder, callback);
    }

    MessageOperation didStartMessageOperation(@NonNull Message message,
                                              @NonNull String conversationId,
                                              @NonNull MessageOperation.Type type) {
        MessageOperation operation = new MessageOperation(message, conversationId, type);
        this.store.setMessageOperations(new MessageOperation[]{operation});
        return operation;
    }

    void didCompleteMessageOperation(@NonNull MessageOperation operation) {
        // Completed message operation is removed from cache store.
        operation.status = MessageOperation.Status.SUCCESS;
        this.store.deleteMessageOperations(new MessageOperation[]{operation});
    }

    void didFailMessageOperation(@NonNull MessageOperation operation, @Nullable Error error) {
        operation.status = MessageOperation.Status.FAILED;
        operation.error = error;
        this.store.setMessageOperations(new MessageOperation[]{operation});
    }

    void didCancelMessageOperation(@NonNull MessageOperation operation) {
        this.store.deleteMessageOperations(new MessageOperation[]{operation});
    }

    void markPendingMessageOperationsAsFailed() {
        RealmStore.QueryBuilder<MessageOperationCacheObject> queryBuilder = new RealmStore.QueryBuilder<MessageOperationCacheObject>() {
            @Override
            public RealmQuery<MessageOperationCacheObject> buildQueryFrom(RealmQuery<MessageOperationCacheObject> baseQuery) {
                RealmQuery<MessageOperationCacheObject> query = baseQuery
                        .equalTo(MessageOperationCacheObject.KEY_STATUS, MessageOperation.Status.PENDING.toString());
                return query;
            }
        };

        this.store.markMessageOperationsAsFailed(queryBuilder, null);
    }

    //endregion
}
