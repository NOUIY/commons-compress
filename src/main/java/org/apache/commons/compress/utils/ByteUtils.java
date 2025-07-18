/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.commons.compress.utils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility methods for reading and writing bytes.
 *
 * @since 1.14
 */
public final class ByteUtils {

    /**
     * Used to consume bytes.
     *
     * @since 1.14
     */
    public interface ByteConsumer {
        /**
         * The contract is similar to {@link OutputStream#write(int)}, consume the lower eight bytes of the int as a byte.
         *
         * @param b the byte to consume
         * @throws IOException if consuming fails
         */
        void accept(int b) throws IOException;
    }

    /**
     * Used to supply bytes.
     *
     * @since 1.14
     */
    public interface ByteSupplier {
        /**
         * The contract is similar to {@link InputStream#read()}, return the byte as an unsigned int, -1 if there are no more bytes.
         *
         * @return the supplied byte or -1 if there are no more bytes
         * @throws IOException if supplying fails
         */
        int getAsByte() throws IOException;
    }

    /**
     * {@link ByteSupplier} based on {@link InputStream}.
     *
     * @since 1.14
     * @deprecated Unused
     */
    @Deprecated
    public static class InputStreamByteSupplier implements ByteSupplier {
        private final InputStream is;

        /**
         * Constructs a new instance.
         *
         * @param is an input stream.
         */
        public InputStreamByteSupplier(final InputStream is) {
            this.is = is;
        }

        @Override
        public int getAsByte() throws IOException {
            return is.read();
        }
    }

    /**
     * {@link ByteConsumer} based on {@link OutputStream}.
     *
     * @since 1.14
     */
    public static class OutputStreamByteConsumer implements ByteConsumer {
        private final OutputStream os;

        /**
         * Constructs a new instance.
         *
         * @param os an output stream.
         */
        public OutputStreamByteConsumer(final OutputStream os) {
            this.os = os;
        }

        @Override
        public void accept(final int b) throws IOException {
            os.write(b);
        }
    }

    /**
     * Empty array.
     *
     * @since 1.21
     */
    public static final byte[] EMPTY_BYTE_ARRAY = {};

    private static void checkReadLength(final int length) {
        if (length > 8) {
            throw new IllegalArgumentException("Can't read more than eight bytes into a long value");
        }
    }

    /**
     * Reads the given byte array as a little-endian long.
     *
     * @param bytes the byte array to convert
     * @return the number read
     */
    public static long fromLittleEndian(final byte[] bytes) {
        return fromLittleEndian(bytes, 0, bytes.length);
    }

    /**
     * Reads the given byte array as a little-endian long.
     *
     * @param bytes  the byte array to convert
     * @param off    the offset into the array that starts the value
     * @param length the number of bytes representing the value
     * @return the number read
     * @throws IllegalArgumentException if len is bigger than eight
     */
    public static long fromLittleEndian(final byte[] bytes, final int off, final int length) {
        checkReadLength(length);
        long l = 0;
        for (int i = 0; i < length; i++) {
            l |= (bytes[off + i] & 0xffL) << 8 * i;
        }
        return l;
    }

    /**
     * Reads the given number of bytes from the given supplier as a little-endian long.
     *
     * <p>
     * Typically used by our InputStreams that need to count the bytes read as well.
     * </p>
     *
     * @param supplier the supplier for bytes
     * @param length   the number of bytes representing the value
     * @return the number read
     * @throws IllegalArgumentException if len is bigger than eight
     * @throws IOException              if the supplier fails or doesn't supply the given number of bytes anymore
     */
    public static long fromLittleEndian(final ByteSupplier supplier, final int length) throws IOException {
        checkReadLength(length);
        long l = 0;
        for (int i = 0; i < length; i++) {
            final long b = supplier.getAsByte();
            if (b == -1) {
                throw new IOException("Premature end of data");
            }
            l |= b << i * 8;
        }
        return l;
    }

    /**
     * Reads the given number of bytes from the given input as little-endian long.
     *
     * @param in     the input to read from
     * @param length the number of bytes representing the value
     * @return the number read
     * @throws IllegalArgumentException if len is bigger than eight
     * @throws IOException              if reading fails or the stream doesn't contain the given number of bytes anymore
     */
    public static long fromLittleEndian(final DataInput in, final int length) throws IOException {
        // somewhat duplicates the ByteSupplier version in order to save the creation of a wrapper object
        checkReadLength(length);
        long l = 0;
        for (int i = 0; i < length; i++) {
            final long b = in.readUnsignedByte();
            l |= b << i * 8;
        }
        return l;
    }

    /**
     * Reads the given number of bytes from the given stream as a little-endian long.
     *
     * @param in     the stream to read from
     * @param length the number of bytes representing the value
     * @return the number read
     * @throws IllegalArgumentException if len is bigger than eight
     * @throws IOException              if reading fails or the stream doesn't contain the given number of bytes anymore
     * @deprecated Unused.
     */
    @Deprecated
    public static long fromLittleEndian(final InputStream in, final int length) throws IOException {
        // somewhat duplicates the ByteSupplier version in order to save the creation of a wrapper object
        checkReadLength(length);
        long l = 0;
        for (int i = 0; i < length; i++) {
            final long b = in.read();
            if (b == -1) {
                throw new IOException("Premature end of data");
            }
            l |= b << i * 8;
        }
        return l;
    }

    /**
     * Inserts the given value into the array as a little-endian sequence of the given length starting at the given offset.
     *
     * @param b      the array to write into
     * @param value  the value to insert
     * @param off    the offset into the array that receives the first byte
     * @param length the number of bytes to use to represent the value
     */
    public static void toLittleEndian(final byte[] b, final long value, final int off, final int length) {
        long num = value;
        for (int i = 0; i < length; i++) {
            b[off + i] = (byte) (num & 0xff);
            num >>= 8;
        }
    }

    /**
     * Provides the given value to the given consumer as a little-endian sequence of the given length.
     *
     * @param consumer the consumer to provide the bytes to
     * @param value    the value to provide
     * @param length   the number of bytes to use to represent the value
     * @throws IOException if writing fails
     */
    public static void toLittleEndian(final ByteConsumer consumer, final long value, final int length) throws IOException {
        long num = value;
        for (int i = 0; i < length; i++) {
            consumer.accept((int) (num & 0xff));
            num >>= 8;
        }
    }

    /**
     * Writes the given value to the given stream as a little-endian array of the given length.
     *
     * @param out    the output to write to
     * @param value  the value to write
     * @param length the number of bytes to use to represent the value
     * @throws IOException if writing fails
     * @deprecated Unused.
     */
    @Deprecated
    public static void toLittleEndian(final DataOutput out, final long value, final int length) throws IOException {
        // somewhat duplicates the ByteConsumer version in order to save the creation of a wrapper object
        long num = value;
        for (int i = 0; i < length; i++) {
            out.write((int) (num & 0xff));
            num >>= 8;
        }
    }

    /**
     * Writes the given value to the given stream as a little-endian array of the given length.
     *
     * @param out    the stream to write to
     * @param value  the value to write
     * @param length the number of bytes to use to represent the value
     * @throws IOException if writing fails
     */
    public static void toLittleEndian(final OutputStream out, final long value, final int length) throws IOException {
        // somewhat duplicates the ByteConsumer version in order to save the creation of a wrapper object
        long num = value;
        for (int i = 0; i < length; i++) {
            out.write((int) (num & 0xff));
            num >>= 8;
        }
    }

    private ByteUtils() {
        /* no instances */ }
}
