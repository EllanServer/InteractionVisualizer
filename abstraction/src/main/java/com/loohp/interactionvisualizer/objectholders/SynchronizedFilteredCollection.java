/*
 * This file is part of InteractionVisualizer.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.interactionvisualizer.objectholders;

import java.lang.reflect.Array;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SynchronizedFilteredCollection<E> implements Collection<E> {

    /**
     * The predicate is evaluated for all elements once only for methods that return an iteration of elements.
     * Meaning it will not return a live view of the underlying collection.<br>
     * <br>
     * However, the SynchronizedFilteredCollection itself is a filtered live view of the underlying collection.<br>
     * <br>
     * "backingCollection" can be another SynchronizedFilteredCollection, in this case their locks will share.
     */
    public static <E> SynchronizedFilteredCollection<E> filter(Collection<E> backingCollection, Predicate<E> predicate) {
        return new SynchronizedFilteredCollection<E>(backingCollection, predicate);
    }

    /**
     * "backingCollection" can be another SynchronizedFilteredCollection, in this case their locks will share.
     */
    public static <E> SynchronizedFilteredCollection<E> from(Collection<E> backingCollection) {
        return new SynchronizedFilteredCollection<E>(backingCollection, e -> true);
    }

    /**
     * Returns a read-only collection that preserves the snapshot-free
     * {@link #anyMatch(Collection, Predicate)} path when the backing collection
     * is a synchronized filtered view.
     */
    public static <E> Collection<E> unmodifiableCollection(Collection<E> backingCollection) {
        return new ReadOnlyCollection<>(Objects.requireNonNull(backingCollection, "backingCollection"));
    }

    /**
     * Tests a collection without materializing an iterator snapshot when it is
     * one of this class's filtered or read-only views. The matcher must be a
     * side-effect-free read: mutating the same collection from the matcher would
     * attempt a read-to-write lock upgrade.
     */
    @SuppressWarnings("unchecked")
    public static <E> boolean anyMatch(Collection<E> collection, Predicate<? super E> matcher) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(matcher, "matcher");
        if (collection instanceof SynchronizedFilteredCollection<?> filtered) {
            return ((SynchronizedFilteredCollection<E>) filtered).anyMatch(matcher);
        }
        if (collection instanceof ReadOnlyCollection<?> readOnly) {
            return ((ReadOnlyCollection<E>) readOnly).anyMatch(matcher);
        }
        for (E element : collection) {
            if (matcher.test(element)) {
                return true;
            }
        }
        return false;
    }

    private final Collection<E> backingCollection;
    private final Predicate<E> predicate;
    private final ReentrantReadWriteLock lock;

    private SynchronizedFilteredCollection(Collection<E> backingCollection, Predicate<E> predicate) {
        this.backingCollection = backingCollection;
        this.predicate = predicate;
        this.lock = acquireLock();
    }

    private ReentrantReadWriteLock acquireLock() {
        if (backingCollection instanceof SynchronizedFilteredCollection) {
            return ((SynchronizedFilteredCollection<E>) backingCollection).acquireLock();
        } else {
            return new ReentrantReadWriteLock();
        }
    }

    public ReentrantReadWriteLock getLock() {
        return lock;
    }

    /**
     * Tests the live filtered view while holding the shared read lock, without
     * materializing the snapshot used by {@link #iterator()} and {@link #stream()}.
     */
    public boolean anyMatch(Predicate<? super E> matcher) {
        Objects.requireNonNull(matcher, "matcher");
        try {
            lock.readLock().lock();
            return anyMatchUnlocked(matcher);
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean anyMatchUnlocked(Predicate<? super E> matcher) {
        if (backingCollection instanceof SynchronizedFilteredCollection<?> filtered) {
            SynchronizedFilteredCollection<E> nested = (SynchronizedFilteredCollection<E>) filtered;
            return nested.anyMatchUnlocked(element -> predicate.test(element) && matcher.test(element));
        }
        for (E element : backingCollection) {
            if (predicate.test(element) && matcher.test(element)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        try {
            lock.readLock().lock();
            return (int) backingCollection.stream().filter(predicate).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        try {
            lock.readLock().lock();
            return backingCollection.stream().noneMatch(predicate);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean contains(Object o) {
        return anyMatch(each -> Objects.equals(each, o));
    }

    @Override
    public Iterator<E> iterator() {
        Iterator<E> itr;
        try {
            lock.readLock().lock();
            itr = backingCollection.stream().filter(predicate).collect(Collectors.toList()).iterator();
        } finally {
            lock.readLock().unlock();
        }
        return new Iterator<E>() {

            private E currentElement = null;

            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public E next() {
                return currentElement = itr.next();
            }

            @Override
            public void remove() {
                if (currentElement == null) {
                    throw new IllegalStateException("Call itr.next() first");
                }
                backingCollection.remove(currentElement);
            }

        };
    }

    @Override
    public Object[] toArray() {
        try {
            lock.readLock().lock();
            return backingCollection.stream().filter(predicate).toArray();
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T[] toArray(T[] a) {
        try {
            lock.readLock().lock();
            return backingCollection.stream().filter(predicate).toArray(size -> a.length >= size ? a : (T[]) Array.newInstance(a.getClass().getComponentType(), size));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean add(E e) {
        if (predicate.test(e)) {
            try {
                lock.writeLock().lock();
                return backingCollection.add(e);
            } finally {
                lock.writeLock().unlock();
            }
        }
        return false;
    }

    @Override
    public boolean remove(Object o) {
        try {
            lock.writeLock().lock();
            return backingCollection.remove(o);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        Collection<E> list;
        try {
            lock.readLock().lock();
            list = backingCollection.stream().filter(predicate).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
        for (Object o : c) {
            if (!list.contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean flag = false;
        try {
            lock.writeLock().lock();
            for (E e : c) {
                if (add(e)) {
                    flag = true;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        return flag;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        try {
            lock.writeLock().lock();
            return backingCollection.removeAll(c);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Predicate<E> test = predicate.and(filter);
        try {
            lock.writeLock().lock();
            return backingCollection.removeIf(test);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean flag = false;
        try {
            lock.writeLock().lock();
            Iterator<E> itr = backingCollection.iterator();
            while (itr.hasNext()) {
                E e = itr.next();
                if (predicate.test(e) && !c.contains(e)) {
                    itr.remove();
                    flag = true;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        return flag;
    }

    @Override
    public void clear() {
        try {
            lock.writeLock().lock();
            backingCollection.removeIf(predicate);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Spliterator<E> spliterator() {
        try {
            lock.readLock().lock();
            return backingCollection.stream().filter(predicate).collect(Collectors.toList()).spliterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<E> stream() {
        try {
            lock.readLock().lock();
            return backingCollection.stream().filter(predicate).collect(Collectors.toList()).stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<E> parallelStream() {
        try {
            lock.readLock().lock();
            return backingCollection.parallelStream().filter(predicate).collect(Collectors.toList()).parallelStream();
        } finally {
            lock.readLock().unlock();
        }
    }

    private static final class ReadOnlyCollection<E> extends AbstractCollection<E> {

        private final Collection<E> backingCollection;
        private final Collection<E> readOnlyDelegate;

        private ReadOnlyCollection(Collection<E> backingCollection) {
            this.backingCollection = backingCollection;
            this.readOnlyDelegate = Collections.unmodifiableCollection(backingCollection);
        }

        private boolean anyMatch(Predicate<? super E> matcher) {
            return SynchronizedFilteredCollection.anyMatch(backingCollection, matcher);
        }

        @Override
        public Iterator<E> iterator() {
            return readOnlyDelegate.iterator();
        }

        @Override
        public int size() {
            return readOnlyDelegate.size();
        }

        @Override
        public boolean isEmpty() {
            return readOnlyDelegate.isEmpty();
        }

        @Override
        public boolean contains(Object object) {
            return readOnlyDelegate.contains(object);
        }

        @Override
        public Object[] toArray() {
            return readOnlyDelegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array) {
            return readOnlyDelegate.toArray(array);
        }

        @Override
        public boolean add(E element) {
            return readOnlyDelegate.add(element);
        }

        @Override
        public boolean remove(Object object) {
            return readOnlyDelegate.remove(object);
        }

        @Override
        public boolean addAll(Collection<? extends E> collection) {
            return readOnlyDelegate.addAll(collection);
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            return readOnlyDelegate.removeAll(collection);
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            return readOnlyDelegate.retainAll(collection);
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            return readOnlyDelegate.removeIf(filter);
        }

        @Override
        public void clear() {
            readOnlyDelegate.clear();
        }
    }

}
