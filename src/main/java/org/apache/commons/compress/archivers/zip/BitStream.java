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

package org.apache.commons.compress.archivers.zip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;

import org.apache.commons.compress.utils.BitInputStream;

/**
 * Iterates over the bits of an InputStream. For each byte the bits are read from the right to the left.
 *
 * @since 1.7
 */
final class BitStream extends BitInputStream {

    BitStream(final InputStream in) {
        super(in, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns the integer value formed by the n next bits (up to 8 bits).
     *
     * @param n the number of bits read (up to 8)
     * @return The value formed by the n bits, or -1 if the end of the stream has been reached
     * @throws IOException on error.
     */
    long nextBits(final int n) throws IOException {
        if (n < 0 || n > 8) {
            throw new IOException("Trying to read " + n + " bits, at most 8 are allowed");
        }
        return readBits(n);
    }

    int nextByte() throws IOException {
        return (int) readBits(8);
    }
}
