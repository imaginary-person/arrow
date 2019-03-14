/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netty.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.memory.AllocationManager.BufferLedger;
import org.apache.arrow.memory.ArrowByteBufAllocator;
import org.apache.arrow.memory.BaseAllocator;
import org.apache.arrow.memory.BaseAllocator.Verbosity;
import org.apache.arrow.memory.BoundsChecking;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.BufferManager;
import org.apache.arrow.memory.util.HistoricalLog;
import org.apache.arrow.util.Preconditions;

import io.netty.util.internal.PlatformDependent;

/**
 * ArrowBuf is the abstraction around raw byte arrays that
 * comprise arrow data structures.
 *
 *
 * <p>Specifically, it serves as a facade over
 * {@linkplain UnsafeDirectLittleEndian} memory objects that hides the details
 * of raw memory addresses.
 *
 * <p>ArrowBuf supports reference counting and ledgering to closely track where
 * memory is being used.
 */
public final class ArrowBuf extends AbstractByteBuf implements AutoCloseable {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ArrowBuf.class);

  private static final AtomicLong idGenerator = new AtomicLong(0);
  private static final int LOG_BYTES_PER_ROW = 10;
  private final long id = idGenerator.incrementAndGet();
  private final AtomicInteger refCnt;
  private final UnsafeDirectLittleEndian udle;
  private final long addr;
  private final int offset;
  private final BufferLedger ledger;
  private final BufferManager bufManager;
  private final ArrowByteBufAllocator alloc;
  private final boolean isEmpty;
  private final HistoricalLog historicalLog = BaseAllocator.DEBUG ?
      new HistoricalLog(BaseAllocator.DEBUG_LOG_LENGTH, "ArrowBuf[%d]", id) : null;
  private volatile int length;

  /**
   * Constructs a new ArrowBuf
   * @param refCnt The atomic integer to use for reference counting this buffer.
   * @param ledger The ledger to use for tracking memory usage of this buffer.
   * @param byteBuf The underlying storage for this buffer.
   * @param manager The manager that handles replacing this buffer.
   * @param alloc The allocator for the buffer (needed for superclass compatibility)
   * @param offset The byte offset into <code>byteBuf</code> this buffer starts at.
   * @param length The  byte length of this buffer
   * @param isEmpty  Indicates if this buffer is empty which enables some optimizations.
   */
  public ArrowBuf(
      final AtomicInteger refCnt,
      final BufferLedger ledger,
      final UnsafeDirectLittleEndian byteBuf,
      final BufferManager manager,
      final ArrowByteBufAllocator alloc,
      final int offset,
      final int length,
      boolean isEmpty) {
    // TODO(emkornfield): Should this be byteBuf.maxCapacity - offset?
    super(byteBuf.maxCapacity());
    this.refCnt = refCnt;
    this.udle = byteBuf;
    this.isEmpty = isEmpty;
    this.bufManager = manager;
    this.alloc = alloc;
    this.addr = byteBuf.memoryAddress() + offset;
    this.ledger = ledger;
    this.length = length;
    this.offset = offset;

    if (BaseAllocator.DEBUG) {
      historicalLog.recordEvent("create()");
    }

  }

  /** Returns a debug friendly string for the given ByteBuf. */
  public static String bufferState(final ByteBuf buf) {
    final int cap = buf.capacity();
    final int mcap = buf.maxCapacity();
    final int ri = buf.readerIndex();
    final int rb = buf.readableBytes();
    final int wi = buf.writerIndex();
    final int wb = buf.writableBytes();
    return String.format("cap/max: %d/%d, ri: %d, rb: %d, wi: %d, wb: %d",
        cap, mcap, ri, rb, wi, wb);
  }

  /**
   * Returns <code>this</code> if size is less then {@link #capacity()}, otherwise
   * delegates to {@link BufferManager#replace(ArrowBuf, int)} to get a new buffer.
   */
  public ArrowBuf reallocIfNeeded(final int size) {
    Preconditions.checkArgument(size >= 0, "reallocation size must be non-negative");

    if (this.capacity() >= size) {
      return this;
    }

    if (bufManager != null) {
      return bufManager.replace(this, size);
    } else {
      throw new UnsupportedOperationException("Realloc is only available in the context of an " +
          "operator's UDFs");
    }
  }

  @Override
  public int refCnt() {
    if (isEmpty) {
      return 1;
    } else {
      return refCnt.get();
    }
  }

  private long addr(int index) {
    return addr + index;
  }

  private final void checkIndexD(int index, int fieldLength) {
    ensureAccessible();
    if (fieldLength < 0) {
      throw new IllegalArgumentException("length: " + fieldLength + " (expected: >= 0)");
    }
    if (index < 0 || index > capacity() - fieldLength) {
      if (BaseAllocator.DEBUG) {
        historicalLog.logHistory(logger);
      }
      throw new IndexOutOfBoundsException(String.format(
          "index: %d, length: %d (expected: range(0, %d))", index, fieldLength, capacity()));
    }
  }

  /**
   * Allows a function to determine whether not reading a particular string of bytes is valid.
   *
   * <p>Will throw an exception if the memory is not readable for some reason. Only doesn't
   * something in the case that
   * AssertionUtil.BOUNDS_CHECKING_ENABLED is true.
   *
   * @param start The starting position of the bytes to be read.
   * @param end   The exclusive endpoint of the bytes to be read.
   */
  public void checkBytes(int start, int end) {
    if (BoundsChecking.BOUNDS_CHECKING_ENABLED) {
      checkIndexD(start, end - start);
    }
  }

  private void chk(int index, int width) {
    if (BoundsChecking.BOUNDS_CHECKING_ENABLED) {
      checkIndexD(index, width);
    }
  }

  private void ensure(int width) {
    if (BoundsChecking.BOUNDS_CHECKING_ENABLED) {
      ensureWritable(width);
    }
  }

  /**
   * Create a new ArrowBuf that is associated with an alternative allocator for the purposes of
   * memory ownership and
   * accounting. This has no impact on the reference counting for the current ArrowBuf except in
   * the situation where the
   * passed in Allocator is the same as the current buffer.
   *
   * <p>This operation has no impact on the reference count of this ArrowBuf. The newly created
   * ArrowBuf with either have a
   * reference count of 1 (in the case that this is the first time this memory is being
   * associated with the new
   * allocator) or the current value of the reference count + 1 for the other
   * AllocationManager/BufferLedger combination
   * in the case that the provided allocator already had an association to this underlying memory.
   *
   * @param target The target allocator to create an association with.
   * @return A new ArrowBuf which shares the same underlying memory as this ArrowBuf.
   */
  public ArrowBuf retain(BufferAllocator target) {

    if (isEmpty) {
      return this;
    }

    if (BaseAllocator.DEBUG) {
      historicalLog.recordEvent("retain(%s)", target.getName());
    }
    final BufferLedger otherLedger = this.ledger.getLedgerForAllocator(target);
    ArrowBuf newArrowBuf = otherLedger.newArrowBuf(offset, length, null);
    newArrowBuf.readerIndex(this.readerIndex);
    newArrowBuf.writerIndex(this.writerIndex);
    return newArrowBuf;
  }

  /**
   * Transfer the memory accounting ownership of this ArrowBuf to another allocator. This will
   * generate a new ArrowBuf
   * that carries an association with the underlying memory of this ArrowBuf. If this ArrowBuf is
   * connected to the
   * owning BufferLedger of this memory, that memory ownership/accounting will be transferred to
   * the target allocator. If
   * this ArrowBuf does not currently own the memory underlying it (and is only associated with
   * it), this does not
   * transfer any ownership to the newly created ArrowBuf.
   *
   * <p>This operation has no impact on the reference count of this ArrowBuf. The newly created
   * ArrowBuf with either have a
   * reference count of 1 (in the case that this is the first time this memory is being
   * associated with the new
   * allocator) or the current value of the reference count for the other
   * AllocationManager/BufferLedger combination in
   * the case that the provided allocator already had an association to this underlying memory.
   *
   * <p>Transfers will always succeed, even if that puts the other allocator into an overlimit
   * situation. This is possible
   * due to the fact that the original owning allocator may have allocated this memory out of a
   * local reservation
   * whereas the target allocator may need to allocate new memory from a parent or RootAllocator.
   * This operation is done
   * in a mostly-lockless but consistent manner. As such, the overlimit==true situation could
   * occur slightly prematurely
   * to an actual overlimit==true condition. This is simply conservative behavior which means we
   * may return overlimit
   * slightly sooner than is necessary.
   *
   * @param target The allocator to transfer ownership to.
   * @return A new transfer result with the impact of the transfer (whether it was overlimit) as
   *         well as the newly created ArrowBuf.
   */
  public TransferResult transferOwnership(BufferAllocator target) {

    if (isEmpty) {
      return new TransferResult(true, this);
    }

    final BufferLedger otherLedger = this.ledger.getLedgerForAllocator(target);
    final ArrowBuf newBuf = otherLedger.newArrowBuf(offset, length, null);
    newBuf.setIndex(this.readerIndex, this.writerIndex);
    final boolean allocationFit = this.ledger.transferBalance(otherLedger);
    return new TransferResult(allocationFit, newBuf);
  }

  @Override
  public boolean release() {
    return release(1);
  }

  /**
   * Release the provided number of reference counts.
   */
  @Override
  public boolean release(int decrement) {

    if (isEmpty) {
      return false;
    }

    if (decrement < 1) {
      throw new IllegalStateException(String.format("release(%d) argument is not positive. Buffer Info: %s",
          decrement, toVerboseString()));
    }

    final int refCnt = ledger.decrement(decrement);

    if (BaseAllocator.DEBUG) {
      historicalLog.recordEvent("release(%d). original value: %d", decrement, refCnt + decrement);
    }

    if (refCnt < 0) {
      throw new IllegalStateException(
          String.format("ArrowBuf[%d] refCnt has gone negative. Buffer Info: %s", id,
              toVerboseString()));
    }

    return refCnt == 0;

  }

  @Override
  public int capacity() {
    return length;
  }

  @Override
  public synchronized ArrowBuf capacity(int newCapacity) {

    if (newCapacity == length) {
      return this;
    }

    Preconditions.checkArgument(newCapacity >= 0);

    if (newCapacity < length) {
      length = newCapacity;
      return this;
    }

    throw new UnsupportedOperationException("Buffers don't support resizing that increases the size.");
  }

  @Override
  public ArrowByteBufAllocator alloc() {
    return alloc;
  }

  @Override
  public ByteOrder order() {
    return ByteOrder.LITTLE_ENDIAN;
  }

  @Override
  public ArrowBuf order(ByteOrder endianness) {
    return this;
  }

  @Override
  public ByteBuf unwrap() {
    return udle;
  }

  @Override
  public boolean isDirect() {
    return true;
  }

  @Override
  public ByteBuf readBytes(int length) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ByteBuf readSlice(int length) {
    final ByteBuf slice = slice(readerIndex(), length);
    readerIndex(readerIndex() + length);
    return slice;
  }

  @Override
  public ByteBuf copy() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ByteBuf copy(int index, int length) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ArrowBuf slice() {
    return slice(readerIndex(), readableBytes());
  }

  @Override
  public ArrowBuf slice(int index, int length) {

    if (isEmpty) {
      return this;
    }

    Preconditions.checkPositionIndex(index, this.length);
    Preconditions.checkPositionIndex(index + length, this.length);

    /*
     * Re the behavior of reference counting, see http://netty.io/wiki/reference-counted-objects
     * .html#wiki-h3-5, which
     * explains that derived buffers share their reference count with their parent
     */
    final ArrowBuf newBuf = ledger.newArrowBuf(offset + index, length);
    newBuf.writerIndex(length);
    return newBuf;
  }

  @Override
  public ArrowBuf duplicate() {
    return slice(0, length);
  }

  @Override
  public int nioBufferCount() {
    return 1;
  }

  @Override
  public ByteBuffer nioBuffer() {
    return nioBuffer(readerIndex(), readableBytes());
  }

  @Override
  public ByteBuffer nioBuffer(int index, int length) {
    return udle.nioBuffer(offset + index, length);
  }

  @Override
  public ByteBuffer internalNioBuffer(int index, int length) {
    return udle.internalNioBuffer(offset + index, length);
  }

  @Override
  public ByteBuffer[] nioBuffers() {
    return new ByteBuffer[] {nioBuffer()};
  }

  @Override
  public ByteBuffer[] nioBuffers(int index, int length) {
    return new ByteBuffer[] {nioBuffer(index, length)};
  }

  @Override
  public boolean hasArray() {
    return udle.hasArray();
  }

  @Override
  public byte[] array() {
    return udle.array();
  }

  @Override
  public int arrayOffset() {
    return udle.arrayOffset();
  }

  @Override
  public boolean hasMemoryAddress() {
    return true;
  }

  @Override
  public long memoryAddress() {
    return this.addr;
  }

  @Override
  public String toString() {
    return String.format("ArrowBuf[%d], udle: [%d %d..%d]", id, udle.id, offset, offset + capacity());
  }

  @Override
  public String toString(Charset charset) {
    return toString(readerIndex, readableBytes(), charset);
  }

  @Override
  public String toString(int index, int length, Charset charset) {

    if (length == 0) {
      return "";
    }

    return ByteBufUtil.decodeString(this, index, length, charset);
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(Object obj) {
    // identity equals only.
    return this == obj;
  }

  @Override
  public ArrowBuf retain(int increment) {
    Preconditions.checkArgument(increment > 0, "retain(%d) argument is not positive", increment);

    if (isEmpty) {
      return this;
    }

    if (BaseAllocator.DEBUG) {
      historicalLog.recordEvent("retain(%d)", increment);
    }

    final int originalReferenceCount = refCnt.getAndAdd(increment);
    Preconditions.checkArgument(originalReferenceCount > 0);
    return this;
  }

  @Override
  public ArrowBuf retain() {
    return retain(1);
  }

  @Override
  public ByteBuf touch() {
    return this;
  }

  @Override
  public ByteBuf touch(Object hint) {
    return this;
  }

  @Override
  public long getLong(int index) {
    chk(index, 8);
    final long v = PlatformDependent.getLong(addr(index));
    return v;
  }

  @Override
  public float getFloat(int index) {
    return Float.intBitsToFloat(getInt(index));
  }

  /**
   * Gets a 64-bit long integer at the specified absolute {@code index} in
   * this buffer in Big Endian Byte Order.
   */
  @Override
  public long getLongLE(int index) {
    chk(index, 8);
    final long v = PlatformDependent.getLong(addr(index));
    return Long.reverseBytes(v);
  }

  @Override
  public double getDouble(int index) {
    return Double.longBitsToDouble(getLong(index));
  }

  @Override
  public char getChar(int index) {
    return (char) getShort(index);
  }

  @Override
  public long getUnsignedInt(int index) {
    return getInt(index) & 0xFFFFFFFFL;
  }

  @Override
  public int getInt(int index) {
    chk(index, 4);
    final int v = PlatformDependent.getInt(addr(index));
    return v;
  }

  /**
   * Gets a 32-bit integer at the specified absolute {@code index} in
   * this buffer in Big Endian Byte Order.
   */
  @Override
  public int getIntLE(int index) {
    chk(index, 4);
    final int v = PlatformDependent.getInt(addr(index));
    return Integer.reverseBytes(v);
  }

  @Override
  public int getUnsignedShort(int index) {
    return getShort(index) & 0xFFFF;
  }

  @Override
  public short getShort(int index) {
    chk(index, 2);
    final short v = PlatformDependent.getShort(addr(index));
    return v;
  }

  /**
   * Gets a 16-bit short integer at the specified absolute {@code index} in
   * this buffer in Big Endian Byte Order.
   */
  @Override
  public short getShortLE(int index) {
    final short v = PlatformDependent.getShort(addr(index));
    return Short.reverseBytes(v);
  }

  /**
   * Gets an unsigned 24-bit medium integer at the specified absolute
   * {@code index} in this buffer.
   */
  @Override
  public int getUnsignedMedium(int index) {
    chk(index, 3);
    final long addr = addr(index);
    return (PlatformDependent.getByte(addr) & 0xff) << 16 |
        (PlatformDependent.getShort(addr + 1) & 0xffff);
  }

  /**
   * Gets an unsigned 24-bit medium integer at the specified absolute {@code index} in
   * this buffer in Big Endian Byte Order.
   */
  @Override
  public int getUnsignedMediumLE(int index) {
    chk(index, 3);
    final long addr = addr(index);
    return (PlatformDependent.getByte(addr) & 0xff) |
        (Short.reverseBytes(PlatformDependent.getShort(addr + 1)) & 0xffff) << 8;
  }

  @Override
  public ArrowBuf setShort(int index, int value) {
    chk(index, 2);
    PlatformDependent.putShort(addr(index), (short) value);
    return this;
  }

  /**
   * Sets the specified 16-bit short integer at the specified absolute {@code index}
   * in this buffer with Big Endian byte order.
   */
  @Override
  public ByteBuf setShortLE(int index, int value) {
    chk(index, 2);
    PlatformDependent.putShort(addr(index), Short.reverseBytes((short) value));
    return this;
  }

  /**
   * Sets the specified 24-bit medium integer at the specified absolute
   * {@code index} in this buffer.
   */
  @Override
  public ByteBuf setMedium(int index, int value) {
    chk(index, 3);
    final long addr = addr(index);
    PlatformDependent.putByte(addr, (byte) (value >>> 16));
    PlatformDependent.putShort(addr + 1, (short) value);
    return this;
  }


  /**
   * Sets the specified 24-bit medium integer at the specified absolute {@code index}
   * in this buffer with Big Endian byte order.
   */
  @Override
  public ByteBuf setMediumLE(int index, int value) {
    chk(index, 3);
    final long addr = addr(index);
    PlatformDependent.putByte(addr, (byte) value);
    PlatformDependent.putShort(addr + 1, Short.reverseBytes((short) (value >>> 8)));
    return this;
  }

  @Override
  public ArrowBuf setInt(int index, int value) {
    chk(index, 4);
    PlatformDependent.putInt(addr(index), value);
    return this;
  }

  /**
   * Sets the specified 32-bit integer at the specified absolute {@code index}
   * in this buffer with Big Endian byte order.
   */
  @Override
  public ByteBuf setIntLE(int index, int value) {
    chk(index, 4);
    PlatformDependent.putInt(addr(index), Integer.reverseBytes(value));
    return this;
  }

  @Override
  public ArrowBuf setLong(int index, long value) {
    chk(index, 8);
    PlatformDependent.putLong(addr(index), value);
    return this;
  }

  /**
   * Sets the specified 64-bit long integer at the specified absolute {@code index}
   * in this buffer with Big Endian byte order.
   */
  @Override
  public ByteBuf setLongLE(int index, long value) {
    chk(index, 8);
    PlatformDependent.putLong(addr(index), Long.reverseBytes(value));
    return this;
  }

  @Override
  public ArrowBuf setChar(int index, int value) {
    chk(index, 2);
    PlatformDependent.putShort(addr(index), (short) value);
    return this;
  }

  @Override
  public ArrowBuf setFloat(int index, float value) {
    chk(index, 4);
    PlatformDependent.putInt(addr(index), Float.floatToRawIntBits(value));
    return this;
  }

  @Override
  public ArrowBuf setDouble(int index, double value) {
    chk(index, 8);
    PlatformDependent.putLong(addr(index), Double.doubleToRawLongBits(value));
    return this;
  }

  @Override
  public ArrowBuf writeShort(int value) {
    ensure(2);
    PlatformDependent.putShort(addr(writerIndex), (short) value);
    writerIndex += 2;
    return this;
  }

  @Override
  public ArrowBuf writeInt(int value) {
    ensure(4);
    PlatformDependent.putInt(addr(writerIndex), value);
    writerIndex += 4;
    return this;
  }

  @Override
  public ArrowBuf writeLong(long value) {
    ensure(8);
    PlatformDependent.putLong(addr(writerIndex), value);
    writerIndex += 8;
    return this;
  }

  @Override
  public ArrowBuf writeChar(int value) {
    ensure(2);
    PlatformDependent.putShort(addr(writerIndex), (short) value);
    writerIndex += 2;
    return this;
  }

  @Override
  public ArrowBuf writeFloat(float value) {
    ensure(4);
    PlatformDependent.putInt(addr(writerIndex), Float.floatToRawIntBits(value));
    writerIndex += 4;
    return this;
  }

  @Override
  public ArrowBuf writeDouble(double value) {
    ensure(8);
    PlatformDependent.putLong(addr(writerIndex), Double.doubleToRawLongBits(value));
    writerIndex += 8;
    return this;
  }

  @Override
  public ArrowBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
    udle.getBytes(index + offset, dst, dstIndex, length);
    return this;
  }

  @Override
  public ArrowBuf getBytes(int index, ByteBuffer dst) {
    udle.getBytes(index + offset, dst);
    return this;
  }

  @Override
  public ArrowBuf setByte(int index, int value) {
    chk(index, 1);
    PlatformDependent.putByte(addr(index), (byte) value);
    return this;
  }

  public void setByte(int index, byte b) {
    chk(index, 1);
    PlatformDependent.putByte(addr(index), b);
  }

  public void writeByteUnsafe(byte b) {
    PlatformDependent.putByte(addr(readerIndex), b);
    readerIndex++;
  }

  @Override
  protected byte _getByte(int index) {
    return getByte(index);
  }

  @Override
  protected short _getShort(int index) {
    return getShort(index);
  }

  /**
   * @see ArrowBuf#getShortLE(int).
   */
  @Override
  protected short _getShortLE(int index) {
    return getShortLE(index);
  }

  @Override
  protected int _getInt(int index) {
    return getInt(index);
  }

  /**
   * @see ArrowBuf#getIntLE(int).
   */
  @Override
  protected int _getIntLE(int index) {
    return getIntLE(index);
  }

  /**
   * @see ArrowBuf#getUnsignedMedium(int).
   */
  @Override
  protected int _getUnsignedMedium(int index) {
    return getUnsignedMedium(index);
  }

  /**
   * @see ArrowBuf#getUnsignedMediumLE(int).
   */
  @Override
  protected int _getUnsignedMediumLE(int index) {
    return getUnsignedMediumLE(index);
  }

  @Override
  protected long _getLong(int index) {
    return getLong(index);
  }

  /**
   * @see ArrowBuf#getLongLE(int).
   */
  @Override
  protected long _getLongLE(int index) {
    return getLongLE(index);
  }

  @Override
  protected void _setByte(int index, int value) {
    setByte(index, value);
  }

  @Override
  protected void _setShort(int index, int value) {
    setShort(index, value);
  }

  /**
   * @see ArrowBuf#setShortLE(int, int).
   */
  @Override
  protected void _setShortLE(int index, int value) {
    setShortLE(index, value);
  }

  @Override
  protected void _setMedium(int index, int value) {
    setMedium(index, value);
  }

  /**
   * @see ArrowBuf#setMediumLE(int, int).
   */
  @Override
  protected void _setMediumLE(int index, int value) {
    setMediumLE(index, value);
  }

  @Override
  protected void _setInt(int index, int value) {
    setInt(index, value);
  }

  /**
   * @see ArrowBuf#setIntLE(int, int).
   */
  @Override
  protected void _setIntLE(int index, int value) {
    setIntLE(index, value);
  }

  @Override
  protected void _setLong(int index, long value) {
    setLong(index, value);
  }

  /**
   * @see ArrowBuf#setLongLE(int, long).
   */
  @Override
  public void _setLongLE(int index, long value) {
    setLongLE(index, value);
  }

  @Override
  public ArrowBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
    udle.getBytes(index + offset, dst, dstIndex, length);
    return this;
  }

  @Override
  public ArrowBuf getBytes(int index, OutputStream out, int length) throws IOException {
    udle.getBytes(index + offset, out, length);
    return this;
  }

  @Override
  public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
    return udle.getBytes(index + offset, out, length);
  }

  @Override
  public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
    return udle.getBytes(index + offset, out, position, length);
  }

  @Override
  public ArrowBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
    udle.setBytes(index + offset, src, srcIndex, length);
    return this;
  }

  /**
   * Copies length bytes from src starting at srcIndex
   * to this buffer starting at index.
   */
  public ArrowBuf setBytes(int index, ByteBuffer src, int srcIndex, int length) {
    if (src.isDirect()) {
      checkIndex(index, length);
      PlatformDependent.copyMemory(PlatformDependent.directBufferAddress(src) + srcIndex, this
              .memoryAddress() + index,
          length);
    } else {
      if (srcIndex == 0 && src.capacity() == length) {
        udle.setBytes(index + offset, src);
      } else {
        ByteBuffer newBuf = src.duplicate();
        newBuf.position(srcIndex);
        newBuf.limit(srcIndex + length);
        udle.setBytes(index + offset, newBuf);
      }
    }

    return this;
  }

  @Override
  public ArrowBuf setBytes(int index, byte[] src, int srcIndex, int length) {
    udle.setBytes(index + offset, src, srcIndex, length);
    return this;
  }

  @Override
  public ArrowBuf setBytes(int index, ByteBuffer src) {
    udle.setBytes(index + offset, src);
    return this;
  }

  @Override
  public int setBytes(int index, InputStream in, int length) throws IOException {
    return udle.setBytes(index + offset, in, length);
  }

  @Override
  public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
    return udle.setBytes(index + offset, in, length);
  }

  @Override
  public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
    return udle.setBytes(index + offset, in, position, length);
  }

  @Override
  public byte getByte(int index) {
    chk(index, 1);
    return PlatformDependent.getByte(addr(index));
  }

  @Override
  public void close() {
    release();
  }

  /**
   * Returns the possible memory consumed by this ArrowBuf in the worse case scenario. (not
   * shared, connected to larger
   * underlying buffer of allocated memory)
   *
   * @return Size in bytes.
   */
  public int getPossibleMemoryConsumed() {
    if (isEmpty) {
      return 0;
    }
    return ledger.getSize();
  }

  /**
   * Return that is Accounted for by this buffer (and its potentially shared siblings within the
   * context of the
   * associated allocator).
   *
   * @return Size in bytes.
   */
  public int getActualMemoryConsumed() {
    if (isEmpty) {
      return 0;
    }
    return ledger.getAccountedSize();
  }

  /**
   * Return the buffer's byte contents in the form of a hex dump.
   *
   * @param start  the starting byte index
   * @param length how many bytes to log
   * @return A hex dump in a String.
   */
  public String toHexString(final int start, final int length) {
    final int roundedStart = (start / LOG_BYTES_PER_ROW) * LOG_BYTES_PER_ROW;

    final StringBuilder sb = new StringBuilder("buffer byte dump\n");
    int index = roundedStart;
    for (int nLogged = 0; nLogged < length; nLogged += LOG_BYTES_PER_ROW) {
      sb.append(String.format(" [%05d-%05d]", index, index + LOG_BYTES_PER_ROW - 1));
      for (int i = 0; i < LOG_BYTES_PER_ROW; ++i) {
        try {
          final byte b = getByte(index++);
          sb.append(String.format(" 0x%02x", b));
        } catch (IndexOutOfBoundsException ioob) {
          sb.append(" <ioob>");
        }
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * Get the integer id assigned to this ArrowBuf for debugging purposes.
   *
   * @return integer id
   */
  public long getId() {
    return id;
  }

  /** Returns all ledger information with stack traces as a string. */
  public String toVerboseString() {
    if (isEmpty) {
      return toString();
    }

    StringBuilder sb = new StringBuilder();
    ledger.print(sb, 0, Verbosity.LOG_WITH_STACKTRACE);
    return sb.toString();
  }

  /**
   * Prints information of this buffer into <code>sb</code> at the given
   * indentation and verbosity level.
   *
   * <p>It will include history if BaseAllocator.DEBUG is true and
   * the verbosity.includeHistoricalLog are true.
   *
   */
  public void print(StringBuilder sb, int indent, Verbosity verbosity) {
    BaseAllocator.indent(sb, indent).append(toString());

    if (BaseAllocator.DEBUG && !isEmpty && verbosity.includeHistoricalLog) {
      sb.append("\n");
      historicalLog.buildHistory(sb, indent + 1, verbosity.includeStackTraces);
    }
  }

  @Override
  public ArrowBuf readerIndex(int readerIndex) {
    super.readerIndex(readerIndex);
    return this;
  }

  @Override
  public ArrowBuf writerIndex(int writerIndex) {
    super.writerIndex(writerIndex);
    return this;
  }

  /**
   * The outcome of a Transfer.
   */
  public class TransferResult {

    /**
     * Whether this transfer fit within the target allocator's capacity.
     */
    public final boolean allocationFit;

    /**
     * The newly created buffer associated with the target allocator.
     */
    public final ArrowBuf buffer;

    private TransferResult(boolean allocationFit, ArrowBuf buffer) {
      this.allocationFit = allocationFit;
      this.buffer = buffer;
    }

  }


}
