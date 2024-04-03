//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;

/**
 * <p>A pooled {@link ByteBuffer} which maintains a reference count that is
 * incremented with {@link #retain()} and decremented with {@link #release()}.</p>
 * <p>The {@code ByteBuffer} is released to a {@link ByteBufferPool}
 * when {@link #release()} is called one more time than {@link #retain()};
 * in such case, the call to {@link #release()} returns {@code true}.</p>
 * <p>A {@code RetainableByteBuffer} can either be:</p>
 * <ul>
 *     <li>in pool; in this case {@link #isRetained()} returns {@code false}
 *     and calling {@link #release()} throws {@link IllegalStateException}</li>
 *     <li>out of pool but not retained; in this case {@link #isRetained()}
 *     returns {@code false} and calling {@link #release()} returns {@code true}</li>
 *     <li>out of pool and retained; in this case {@link #isRetained()}
 *     returns {@code true} and calling {@link #release()} returns {@code false}</li>
 * </ul>
 */
public interface RetainableByteBuffer extends Retainable
{
    /**
     * A Zero-capacity, non-retainable {@code RetainableByteBuffer}.
     */
    RetainableByteBuffer EMPTY = wrap(BufferUtil.EMPTY_BUFFER);

    /**
     * <p>Returns a non-retainable {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer}.</p>
     * <p>Use this method to wrap user-provided {@code ByteBuffer}s, or
     * {@code ByteBuffer}s that hold constant bytes, to make them look
     * like {@code RetainableByteBuffer}s.</p>
     * <p>The returned {@code RetainableByteBuffer} {@link #canRetain()}
     * method always returns {@code false}.</p>
     * <p>{@code RetainableByteBuffer}s returned by this method are not
     * suitable to be wrapped in other {@link Retainable} implementations
     * that may delegate calls to {@link #retain()}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @return a non-retainable {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer)
    {
        return new NonRetainableByteBuffer(byteBuffer);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Retainable}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param retainable the associated {@link Retainable}.
     * @return a {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new RetainableByteBuffer.Mutable()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return byteBuffer;
            }

            @Override
            public boolean isRetained()
            {
                return retainable.isRetained();
            }

            @Override
            public boolean canRetain()
            {
                return retainable.canRetain();
            }

            @Override
            public void retain()
            {
                retainable.retain();
            }

            @Override
            public boolean release()
            {
                return retainable.release();
            }
        };
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Runnable} releaser.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param releaser a {@link Runnable} to call when the buffer is released.
     * @return a {@code RetainableByteBuffer}
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Runnable releaser)
    {
        return new AbstractRetainableByteBuffer(byteBuffer)
        {
            {
                acquire();
            }

            @Override
            public boolean release()
            {
                boolean released = super.release();
                if (released)
                    releaser.run();
                return released;
            }
        };
    }

    /**
     * Get a mutable representation of this buffer.
     * @return A {@link Mutable} version of this buffer, sharing both data and position pointers.
     * @throws ReadOnlyBufferException if this {@link RetainableByteBuffer}
     *         implementation does not support the {@link Mutable} API.
     */
    default Mutable asMutable() throws ReadOnlyBufferException
    {
        throw new ReadOnlyBufferException();
    }

    /**
     * Appends and consumes the contents of this buffer to the passed buffer, limited by the capacity of the target buffer.
     * @param buffer The buffer to append bytes to, whose limit will be updated.
     * @return {@code true} if all bytes in this buffer are able to be appended.
     * @see #putTo(ByteBuffer)
     */
    default boolean appendTo(ByteBuffer buffer)
    {
        return remaining() == BufferUtil.append(buffer, getByteBuffer());
    }

    /**
     * Appends and consumes the contents of this buffer to the passed buffer, limited by the capacity of the target buffer.
     * @param buffer The buffer to append bytes to, whose limit will be updated.
     * @return {@code true} if all bytes in this buffer are able to be appended.
     * @see #putTo(ByteBuffer)
     */
    default boolean appendTo(RetainableByteBuffer buffer)
    {
        return buffer.asMutable().append(getByteBuffer());
    }

    /**
     * Creates a deep copy of this RetainableByteBuffer that is entirely independent
     * @return A copy of this RetainableByteBuffer
     */
    default RetainableByteBuffer copy()
    {
        return new AbstractRetainableByteBuffer(BufferUtil.copy(getByteBuffer()))
        {
            {
                acquire();
            }
        };
    }

    /**
     * Consumes and returns a byte from this RetainableByteBuffer
     *
     * @return the byte
     * @throws BufferUnderflowException if the buffer is empty.
     */
    default byte get() throws BufferUnderflowException
    {
        return getByteBuffer().get();
    }

    /**
     * Consumes and copies the bytes from this RetainableByteBuffer to the given byte array.
     *
     * @param bytes the byte array to copy the bytes into
     * @param offset the offset within the byte array
     * @param length the maximum number of bytes to copy
     * @return the number of bytes actually copied
     */
    default int get(byte[] bytes, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(bytes, offset, length);
        return length;
    }

    /**
     * Get the wrapped, not {@code null}, {@code ByteBuffer}.
     * @return the wrapped, not {@code null}, {@code ByteBuffer}
     */
    ByteBuffer getByteBuffer();

    /**
     * @return whether the {@code ByteBuffer} is direct
     */
    default boolean isDirect()
    {
        return getByteBuffer().isDirect();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes left for reading
     */
    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes left for appending
     */
    default boolean isFull()
    {
        return space() == 0;
    }

    /**
     * Consumes and puts the contents of this retainable byte buffer at the end of the given byte buffer.
     * @param toInfillMode the destination buffer, whose position is updated.
     * @throws BufferOverflowException – If there is insufficient space in this buffer for the remaining bytes in the source buffer
     * @see ByteBuffer#put(ByteBuffer)
     */
    default void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
    {
        toInfillMode.put(getByteBuffer());
    }

    /**
     * @return the number of remaining bytes in the {@code ByteBuffer}
     */
    default int remaining()
    {
        return getByteBuffer().remaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes
     */
    default boolean hasRemaining()
    {
        return getByteBuffer().hasRemaining();
    }

    /**
     * @return the {@code ByteBuffer} capacity
     */
    default int capacity()
    {
        return getByteBuffer().capacity();
    }

    /**
     * @see BufferUtil#clear(ByteBuffer)
     */
    default void clear()
    {
        BufferUtil.clear(getByteBuffer());
    }

    /**
     * <p>Skips, advancing the ByteBuffer position, the given number of bytes.</p>
     *
     * @param length the maximum number of bytes to skip
     * @return the number of bytes actually skipped
     */
    default long skip(long length)
    {
        if (length == 0)
            return 0;
        ByteBuffer byteBuffer = getByteBuffer();
        length = Math.min(byteBuffer.remaining(), length);
        byteBuffer.position(byteBuffer.position() + Math.toIntExact(length));
        return length;
    }

    /**
     * Get a slice of the buffer.
     * @return A sliced {@link RetainableByteBuffer} sharing this buffers data and reference count, but
     *         with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice()
    {
        if (canRetain())
            retain();
        return RetainableByteBuffer.wrap(getByteBuffer().slice(), this);
    }

    /**
     * Get a partial slice of the buffer.
     * @param length The number of bytes to slice.
     * @return A sliced {@link RetainableByteBuffer} sharing the first {@code length} bytes of this buffers data and
     * reference count, but with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice(long length)
    {
        if (canRetain())
            retain();
        ByteBuffer slice = getByteBuffer().slice();
        slice.limit(slice.position() + Math.toIntExact(length));
        return RetainableByteBuffer.wrap(slice, this);
    }

    /**
     * @return the number of bytes left for appending in the {@code ByteBuffer}
     */
    default int space()
    {
        return capacity() - remaining();
    }

    /**
     * Asynchronously writes and consumes the contents of this retainable byte buffer into given sink.
     * @param sink the destination sink.
     * @param last true if this is the last write.
     * @param callback the callback to call upon the write completion.
     * @see org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)
     */
    default void writeTo(Content.Sink sink, boolean last, Callback callback)
    {
        sink.write(last, getByteBuffer(), callback);
    }

    /**
     * Convert Buffer to a detail debug string of pointers and content
     *
     * @return A string showing the pointers and content of the buffer
     */
    default String toDetailString()
    {
        StringBuilder buf = new StringBuilder();

        buf.append(getClass().getSimpleName());
        buf.append("@");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append("[c=");
        buf.append(capacity());
        buf.append(",r=");
        buf.append(remaining());
        buf.append("]={");
        appendDebugString(buf, this);
        buf.append("}");
        return buf.toString();
    }

    /**
     * A wrapper for {@link RetainableByteBuffer} instances
     */
    class Wrapper extends Retainable.Wrapper implements RetainableByteBuffer
    {
        public Wrapper(RetainableByteBuffer wrapped)
        {
            super(wrapped);
        }

        public RetainableByteBuffer getWrapped()
        {
            return (RetainableByteBuffer)super.getWrapped();
        }

        @Override
        public boolean isRetained()
        {
            return getWrapped().isRetained();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return getWrapped().getByteBuffer();
        }

        @Override
        public boolean isDirect()
        {
            return getWrapped().isDirect();
        }

        @Override
        public int remaining()
        {
            return getWrapped().remaining();
        }

        @Override
        public boolean hasRemaining()
        {
            return getWrapped().hasRemaining();
        }

        @Override
        public int capacity()
        {
            return getWrapped().capacity();
        }

        @Override
        public void clear()
        {
            getWrapped().clear();
        }

        @Override
        public boolean canRetain()
        {
            return getWrapped().canRetain();
        }

        @Override
        public void retain()
        {
            getWrapped().retain();
        }

        @Override
        public boolean release()
        {
            return getWrapped().release();
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), getWrapped().toString());
        }

        @Override
        public boolean appendTo(ByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public boolean appendTo(RetainableByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return getWrapped().copy();
        }

        @Override
        public int get(byte[] bytes, int offset, int length)
        {
            return getWrapped().get(bytes, offset, length);
        }

        @Override
        public boolean isEmpty()
        {
            return getWrapped().isEmpty();
        }

        @Override
        public boolean isFull()
        {
            return getWrapped().isFull();
        }

        @Override
        public void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
        {
            getWrapped().putTo(toInfillMode);
        }

        @Override
        public long skip(long length)
        {
            return getWrapped().skip(length);
        }

        @Override
        public RetainableByteBuffer slice()
        {
            return getWrapped().slice();
        }

        @Override
        public int space()
        {
            return getWrapped().space();
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            getWrapped().writeTo(sink, last, callback);
        }
    }

    interface Mutable extends RetainableByteBuffer
    {
        @Override
        default Mutable asMutable() throws ReadOnlyBufferException
        {
            return this;
        }

        /**
         * @return the number of bytes left for appending in the {@code ByteBuffer}
         */
        default int space()
        {
            return capacity() - remaining();
        }

        /**
         * @return whether the {@code ByteBuffer} has remaining bytes left for appending
         */
        default boolean isFull()
        {
            return space() == 0;
        }

        /**
         * Copies the contents of the given byte buffer at the end of this buffer.
         * @param bytes the byte buffer to copy from.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if the buffer is read only
         * @see BufferUtil#append(ByteBuffer, ByteBuffer)
         */
        default boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            BufferUtil.append(getByteBuffer(), bytes);
            return !bytes.hasRemaining();
        }

        /**
         * Copies the contents of the given retainable byte buffer at the end of this buffer.
         * @param bytes the retainable byte buffer to copy from.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if the buffer is read only
         * @see BufferUtil#append(ByteBuffer, ByteBuffer)
         */
        default boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            return bytes.remaining() == 0 || append(bytes.getByteBuffer());
        }

        /**
         * A wrapper for {@link RetainableByteBuffer} instances
         */
        class Wrapper extends RetainableByteBuffer.Wrapper implements Mutable
        {
            public Wrapper(RetainableByteBuffer.Mutable wrapped)
            {
                super(wrapped);
            }
        }
    }

    /**
     * An aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
     */
    class Aggregator implements RetainableByteBuffer.Mutable
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final int _growBy;
        private final int _maxCapacity;
        private RetainableByteBuffer.Mutable _buffer;

        /**
         * Construct an aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for 2GB limit.
         *                    Note that the pool may provide a buffer that exceeds this capacity.
         */
        public Aggregator(ByteBufferPool pool, boolean direct, int maxCapacity)
        {
            this(pool, direct, -1, maxCapacity);
        }

        /**
         * Construct an aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param growBy the size to grow the buffer by or &lt;= 0 for a heuristic
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for 2GB limit.
         *                    Note that the pool may provide a buffer that exceeds this capacity.
         */
        public Aggregator(ByteBufferPool pool, boolean direct, int growBy, int maxCapacity)
        {
            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _maxCapacity = maxCapacity <= 0 ? Integer.MAX_VALUE : maxCapacity;

            if (growBy <= 0)
            {
                _buffer = _pool.acquire(Math.min(4096, _maxCapacity), _direct).asMutable();
                _growBy = Math.min(_maxCapacity, _buffer.capacity());
            }
            else
            {
                if (growBy > _maxCapacity)
                    throw new IllegalArgumentException("growBy(%d) must be <= maxCapacity(%d)".formatted(growBy, _maxCapacity));

                _growBy = growBy;
                _buffer = _pool.acquire(_growBy, _direct).asMutable();
            }
        }

        @Override
        public void clear()
        {
            if (isRetained())
            {
                _buffer.release();
                _buffer = _pool.acquire(_growBy, _direct).asMutable();
            }
            else
            {
                BufferUtil.clear(_buffer.getByteBuffer());
            }
        }

        @Override
        public boolean canRetain()
        {
            return _buffer.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return _buffer.isRetained();
        }

        @Override
        public void retain()
        {
            _buffer.retain();
        }

        @Override
        public boolean release()
        {
            return _buffer.release();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return _buffer.getByteBuffer();
        }

        @Override
        public RetainableByteBuffer copy()
        {
            RetainableByteBuffer buffer = _buffer;
            buffer.retain();
            return new AbstractRetainableByteBuffer(buffer.getByteBuffer().slice())
            {
                {
                    acquire();
                }

                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        buffer.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public int capacity()
        {
            return Math.max(_buffer.capacity(), _maxCapacity);
        }

        @Override
        public boolean append(ByteBuffer bytes)
        {
            ensureSpace(bytes.remaining());
            BufferUtil.append(_buffer.getByteBuffer(), bytes);
            return !bytes.hasRemaining();
        }

        @Override
        public boolean append(RetainableByteBuffer bytes)
        {
            return append(bytes.getByteBuffer());
        }

        private void ensureSpace(int spaceNeeded)
        {
            int capacity = _buffer.capacity();
            int space = capacity - _buffer.remaining();
            if (spaceNeeded <= space || capacity >= _maxCapacity)
                return;

            int newCapacity = Math.multiplyExact(1 + Math.addExact(capacity, spaceNeeded) / _growBy,  _growBy);
            if (newCapacity > _maxCapacity)
            {
                newCapacity = Math.addExact(capacity, spaceNeeded - space);
                if (newCapacity > _maxCapacity)
                    newCapacity = _maxCapacity;
            }

            RetainableByteBuffer.Mutable ensured = _pool.acquire(newCapacity, _direct).asMutable();
            ensured.append(_buffer);
            _buffer.release();
            _buffer = ensured;
        }
    }

    /**
     * An accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
     * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
     */
    class Accumulator implements RetainableByteBuffer.Mutable
    {
        private final Retainable _retainable = new ReferenceCounter();
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxLength;
        private final List<RetainableByteBuffer> _buffers;

        /**
         * Construct an accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxLength The maximum length of the accumulated buffers or -1 for 2GB limit
         */
        public Accumulator(ByteBufferPool pool, boolean direct, long maxLength)
        {
            this(new ArrayList<>(), pool, direct, maxLength);
        }

        private Accumulator(List<RetainableByteBuffer> buffers, ByteBufferPool pool, boolean direct, long maxLength)
        {
            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _maxLength = maxLength < 0 ? Long.MAX_VALUE : maxLength;
            _buffers = buffers;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return switch (_buffers.size())
            {
                case 0 -> RetainableByteBuffer.EMPTY.getByteBuffer();
                case 1 -> _buffers.get(0).getByteBuffer();
                default ->
                {
                    RetainableByteBuffer combined = copy(true);
                    _buffers.add(combined);
                    yield combined.getByteBuffer();
                }
            };
        }

        @Override
        public byte get() throws BufferUnderflowException
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                    continue;
                }

                byte b = buffer.get();
                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
                return b;
            }
            throw new BufferUnderflowException();
        }

        @Override
        public int get(byte[] bytes, int offset, int length)
        {
            int got = 0;
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); length > 0 && i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                int l = buffer.get(bytes, offset, length);
                got += l;
                offset += l;
                length -= l;

                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
            }
            return got;
        }

        @Override
        public boolean isDirect()
        {
            return _direct;
        }

        @Override
        public boolean isEmpty()
        {
            return Mutable.super.isEmpty();
        }

        @Override
        public boolean hasRemaining()
        {
            for (RetainableByteBuffer rbb : _buffers)
                if (!rbb.isEmpty())
                    return true;
            return false;
        }

        @Override
        public long skip(long length)
        {
            long skipped = 0;
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); length > 0 && i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                long skip = buffer.skip(length);
                skipped += skip;
                length -= skip;

                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
            }
            return skipped;
        }

        @Override
        public RetainableByteBuffer slice()
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.slice());
            retain();
            Accumulator parent = this;
            return new Accumulator(buffers, _pool, _direct, _maxLength)
            {
                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        parent.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public RetainableByteBuffer slice(long length)
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
            {
                int l = rbb.remaining();

                if (l > length)
                {
                    buffers.add(rbb.slice(length));
                    break;
                }

                buffers.add(rbb.slice());
                length -= l;
            }

            retain();
            Accumulator parent = this;
            return new Accumulator(buffers, _pool, _direct, _maxLength)
            {
                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        parent.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public int space()
        {
            return Math.toIntExact(spaceLong());
        }

        public long spaceLong()
        {
            return capacityLong() - remainingLong();
        }

        @Override
        public boolean isFull()
        {
            return spaceLong() <= 0;
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return copy(false);
        }

        private RetainableByteBuffer copy(boolean take)
        {
            int length = remaining();
            RetainableByteBuffer combinedBuffer = _pool.acquire(length, _direct);
            ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
            BufferUtil.flipToFill(byteBuffer);
            for (RetainableByteBuffer buffer : _buffers)
            {
                byteBuffer.put(buffer.getByteBuffer().slice());
                if (take)
                    buffer.release();
            }
            BufferUtil.flipToFlush(byteBuffer, 0);
            if (take)
                _buffers.clear();
            return combinedBuffer;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the length of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}
         */
        @Override
        public int remaining()
        {
            long remainingLong = remainingLong();
            return remainingLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(remainingLong);
        }

        public long remainingLong()
        {
            long length = 0;
            for (RetainableByteBuffer buffer : _buffers)
                length += buffer.remaining();
            return length;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the maxLength of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}.
         */
        @Override
        public int capacity()
        {
            long capacityLong = capacityLong();
            return capacityLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(capacityLong);
        }

        public long capacityLong()
        {
            return _maxLength;
        }

        @Override
        public boolean canRetain()
        {
            return _retainable.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return _retainable.isRetained();
        }

        @Override
        public void retain()
        {
            _retainable.retain();
        }

        @Override
        public boolean release()
        {
            if (_retainable.release())
            {
                clear();
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.release();
            _buffers.clear();
        }

        @Override
        public boolean append(ByteBuffer bytes)
        {
            int remaining = bytes.remaining();
            if (remaining == 0)
                return true;

            long currentlyRemaining = _maxLength - remainingLong();
            if (currentlyRemaining >= remaining)
            {
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(bytes.slice());
                bytes.position(bytes.limit());
                _buffers.add(rbb);
                return true;
            }
            else
            {
                ByteBuffer slice = bytes.slice();
                slice.limit((int)(slice.position() + currentlyRemaining));
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(slice);
                bytes.position((int)(bytes.position() + currentlyRemaining));
                _buffers.add(rbb);
                return false;
            }
        }

        @Override
        public boolean append(RetainableByteBuffer retainableBytes)
        {
            ByteBuffer bytes = retainableBytes.getByteBuffer();
            int remaining = bytes.remaining();
            if (remaining == 0)
                return true;

            long currentlyRemaining = _maxLength - remainingLong();
            if (currentlyRemaining >= remaining)
            {
                retainableBytes.retain();
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(bytes.slice(), retainableBytes);
                bytes.position(bytes.limit());
                _buffers.add(rbb);
                return true;
            }
            else
            {
                retainableBytes.retain();
                ByteBuffer slice = bytes.slice();
                slice.limit((int)(slice.position() + currentlyRemaining));
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(slice, retainableBytes);
                bytes.position((int)(bytes.position() + currentlyRemaining));
                _buffers.add(rbb);
                return false;
            }
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                buffer.putTo(toInfillMode);
                buffer.release();
                i.remove();
            }
        }

        @Override
        public boolean appendTo(ByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public boolean appendTo(RetainableByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    buffer.writeTo(sink, last, Callback.from(() ->
                    {
                        if (!buffer.hasRemaining())
                        {
                            buffer.release();
                            _buffers.clear();
                        }
                    }, callback));
                }
                default -> new IteratingNestedCallback(callback)
                {
                    boolean _lastWritten;

                    @Override
                    protected Action process()
                    {
                        while (true)
                        {
                            if (_buffers.isEmpty())
                            {
                                if (last && !_lastWritten)
                                {
                                    _lastWritten = true;
                                    sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                                    return Action.SCHEDULED;
                                }
                                return Action.SUCCEEDED;
                            }

                            RetainableByteBuffer buffer = _buffers.get(0);
                            if (buffer.hasRemaining())
                            {
                                _lastWritten = last && _buffers.size() == 1;
                                buffer.writeTo(sink, _lastWritten, this);
                                return Action.SCHEDULED;
                            }

                            buffer.release();
                            _buffers.remove(0);
                        }
                    }
                }.iterate();
            }
        }
    }

    private static void appendDebugString(StringBuilder buf, RetainableByteBuffer buffer)
    {
        // Take a slice so we can adjust the limit
        RetainableByteBuffer slice = buffer.slice();
        try
        {
            buf.append("<<<");

            int size = slice.remaining();

            int skip = Math.max(0, size - 32);

            int bytes = 0;
            while (slice.remaining() > 0)
            {
                BufferUtil.appendDebugByte(buf, slice.get());
                if (skip > 0 && ++bytes == 16)
                {
                    buf.append("...");
                    slice.skip(skip);
                }
            }
            buf.append(">>>");
        }
        catch (Throwable x)
        {
            buf.append("!!concurrent mod!!");
        }
        finally
        {
            slice.release();
        }
    }
}
