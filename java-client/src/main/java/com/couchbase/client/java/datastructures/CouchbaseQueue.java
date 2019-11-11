/*
 * Copyright (c) 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.java.datastructures;

import java.util.AbstractQueue;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Queue;

import com.couchbase.client.core.annotation.Stability;


import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.error.subdoc.PathNotFoundException;
import com.couchbase.client.core.msg.kv.SubDocumentOpResponseStatus;
import com.couchbase.client.core.retry.reactor.RetryExhaustedException;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.core.error.CASMismatchException;
import com.couchbase.client.core.error.subdoc.MultiMutationException;
import com.couchbase.client.java.kv.CommonDatastructureOptions;
import com.couchbase.client.java.kv.GetOptions;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.InsertOptions;
import com.couchbase.client.java.kv.LookupInOptions;
import com.couchbase.client.java.kv.LookupInResult;
import com.couchbase.client.java.kv.LookupInSpec;
import com.couchbase.client.java.kv.MutateInOptions;
import com.couchbase.client.java.kv.MutateInResult;
import com.couchbase.client.java.kv.MutateInSpec;
import com.couchbase.client.java.kv.QueueOptions;
import com.couchbase.client.java.kv.StoreSemantics;
import com.couchbase.client.java.kv.UpsertOptions;

/**
 * A CouchbaseQueue is a {@link Queue} backed by a {@link Collection Couchbase} document (more
 * specifically a {@link JsonArray JSON array}).
 *
 * Note that as such, a CouchbaseQueue is restricted to the types that a {@link JsonArray JSON array}
 * can contain. JSON objects and sub-arrays can be represented as {@link JsonObject} and {@link JsonArray}
 * respectively. Null values are not allowed as they have special meaning for the {@link #peek()} and {@link #remove()}
 * methods of a queue.
 *
 * @param <E> the type of values in the queue.
 *
 * @since 2.3.6
 */

@Stability.Committed
public class CouchbaseQueue<E> extends AbstractQueue<E> {

    private final String id;
    private final Collection collection;
    private final Class<E> entityTypeClass;
    private final QueueOptions.Built queueOptions;
    private final GetOptions getOptions;
    private final LookupInOptions lookupInOptions;
    private final MutateInOptions mutateInOptions;
    private final InsertOptions insertOptions;
    private final UpsertOptions upsertOptions;

    /**
     * Create a new {@link Collection Couchbase-backed} Queue, backed by the document identified by <code>id</code>
     * in <code>bucket</code>. Note that if the document already exists, its content will be used as initial
     * content for this collection. Otherwise it is created empty.
     *
     * @param id the id of the Couchbase document to back the queue.
     * @param collection the {@link Collection} through which to interact with the document.
     * @param entityType a {@link Class<E>} describing the type of objects in this Set.
     * @param options a {@link CommonDatastructureOptions} to use for all operations on this instance of the queue.
     */
    public CouchbaseQueue(String id, Collection collection, Class<E> entityType, QueueOptions options) {
        this.collection = collection;
        this.id = id;
        this.entityTypeClass = entityType;

        // copy the options just in case they are reused later
        QueueOptions.Built optionsIn = options.build();
        QueueOptions opts = QueueOptions.queueOptions();
        optionsIn.copyInto(opts);
        this.queueOptions = opts.build();
        this.getOptions = optionsIn.getOptions();
        this.lookupInOptions = optionsIn.lookupInOptions();
        this.upsertOptions = optionsIn.upsertOptions();
        this.insertOptions = optionsIn.insertOptions();
        this.mutateInOptions = optionsIn.mutateInOptions();
    }

    /**
     * Create a new {@link Collection Couchbase-backed} Queue, backed by the document identified by <code>id</code>
     * in <code>bucket</code>. Note that if the document already exists, its content will be used as initial
     * content for this collection. Otherwise it is created empty.
     *
     * @param id the id of the Couchbase document to back the queue.
     * @param collection the {@link Collection} through which to interact with the document.
     * @param entityType a {@link Class<E>} describing the type of objects in this Set.
     */
    public CouchbaseQueue(String id, Collection collection, Class<E> entityType) {
        this(id, collection, entityType, QueueOptions.queueOptions());
    }

    @Override
    public Iterator<E> iterator() { return new CouchbaseQueueIterator<>(); }

    @Override
    public int size() {
        try {
            LookupInResult result = collection.lookupIn(id,
                    Collections.singletonList(LookupInSpec.count("")),
                    lookupInOptions);
            return result.contentAs(0, Integer.class);
        } catch (DocumentNotFoundException e) {
            return 0;
        }
    }

    @Override
    public void clear() {
        collection.remove(id);
    }

    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException("Unsupported null value");
        }
        collection.mutateIn(id,
                Collections.singletonList(MutateInSpec.arrayPrepend("", e)),
                queueOptions.mutateInOptions().storeSemantics(StoreSemantics.UPSERT));
        return true;
    }

    @Override
    public E poll() {
        String idx = "[-1]"; //FIFO queue as offer uses ARRAY_PREPEND
        for(int i = 0; i < queueOptions.casMismatchRetries(); i++) {
            try {
                LookupInResult result = collection.lookupIn(id,
                        Collections.singletonList(LookupInSpec.get(idx)),
                        lookupInOptions);
                E current = result.contentAs(0, entityTypeClass);
                long returnCas = result.cas();
                collection.mutateIn(id,
                        Collections.singletonList(MutateInSpec.remove(idx)),
                        queueOptions.mutateInOptions().cas(returnCas));
                return current;
            } catch (DocumentNotFoundException ex) {
                return null;
            } catch (CASMismatchException ex) {
                //will have to retry get-and-remove
            } catch (MultiMutationException ex) {
                if (ex.firstFailureStatus() == SubDocumentOpResponseStatus.PATH_NOT_FOUND) {
                    // queue is empty
                    return null;
                }
                throw ex;
            }
        }
        throw new RetryExhaustedException("Couldn't perform set in less than " + queueOptions.casMismatchRetries() + " iterations.  It is likely concurrent modifications of this document are the reason");
    }

    @Override
    public E peek() {
        try {
            LookupInResult result = collection.lookupIn(id,
                    Collections.singletonList(LookupInSpec.get("[-1]")),
                    lookupInOptions);
            return result.contentAs(0, entityTypeClass);
        } catch (DocumentNotFoundException e) {
            return null;
        } catch (PathNotFoundException e) {
                return null; //the queue is empty
        }
    }

    public class CouchbaseQueueIterator<E> implements Iterator<E> {

        private long cas;
        private final Iterator<E> delegate;
        private int lastVisited = -1;
        private boolean doneRemove = false;

        public CouchbaseQueueIterator() {
            JsonArray content;
            try {
                GetResult result = collection.get(id);
                this.cas = result.cas();
                content = result.contentAsArray();
            } catch (DocumentNotFoundException e) {
                this.cas = 0;
                content = JsonArray.empty();
            }
            this.delegate = (Iterator<E>) content.iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public E next() {
            if (hasNext()) {
                lastVisited++;
                doneRemove = false;
            }
            return delegate.next();
        }

        @Override
        public void remove() {
            if (lastVisited < 0) {
                throw new IllegalStateException("Cannot remove before having started iterating");
            }
            //skip remove attempts past the first one after a next()
            if (doneRemove) {
                throw new IllegalStateException("Cannot remove twice in a row while iterating");
            }
            String path = "[" + lastVisited + "]";
            //use the cas to attempt to remove
            try {
                MutateInResult result = collection.mutateIn(id, Collections.singletonList(MutateInSpec.remove(path)),
                        queueOptions.mutateInOptions().cas(this.cas));
                //update the cas
                this.cas = result.cas();
                //ok the remove succeeded in DB, let's reflect that in the iterator's backing collection and state
                delegate.remove();
                doneRemove = true;
                lastVisited--;
            } catch (CASMismatchException | DocumentNotFoundException e) {
                throw new ConcurrentModificationException("Couldn't remove while iterating: " + e);
            } catch (MultiMutationException ex) {
                if (ex.firstFailureStatus() == SubDocumentOpResponseStatus.PATH_NOT_FOUND) {
                    // queue is empty
                    return;
                }
                throw ex;
           }
        }
    }}