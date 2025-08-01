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
package org.apache.commons.compress.compressors.lz77support;

import java.io.IOException;
import java.util.Objects;

import org.apache.commons.lang3.ArrayFill;

/**
 * Helper class for compression algorithms that use the ideas of LZ77.
 *
 * <p>
 * Most LZ77 derived algorithms split input data into blocks of uncompressed data (called literal blocks) and back-references (pairs of offsets and lengths)
 * that state "add {@code length} bytes that are the same as those already written starting {@code offset} bytes before the current position. The details of how
 * those blocks and back-references are encoded are quite different between the algorithms and some algorithms perform additional steps (Huffman encoding in the
 * case of DEFLATE for example).
 * </p>
 *
 * <p>
 * This class attempts to extract the core logic - finding back-references - so it can be re-used. It follows the algorithm explained in section 4 of RFC 1951
 * (DEFLATE) and currently doesn't implement the "lazy match" optimization. The three-byte hash function used in this class is the same as the one used by zlib
 * and InfoZIP's ZIP implementation of DEFLATE. The whole class is strongly inspired by InfoZIP's implementation.
 * </p>
 *
 * <p>
 * LZ77 is used vaguely here (as well as many other places that talk about it :-), LZSS would likely be closer to the truth but LZ77 has become the synonym for
 * a whole family of algorithms.
 * </p>
 *
 * <p>
 * The API consists of a compressor that is fed {@code byte}s and emits {@link Block}s to a registered callback where the blocks represent either
 * {@link LiteralBlock literal blocks}, {@link BackReference back-references} or {@link EOD end of data markers}. In order to ensure the callback receives all
 * information, the {@code #finish} method must be used once all data has been fed into the compressor.
 * </p>
 *
 * <p>
 * Several parameters influence the outcome of the "compression":
 * </p>
 * <dl>
 *
 * <dt>{@code windowSize}</dt>
 * <dd>the size of the sliding window, must be a power of two - this determines the maximum offset a back-reference can take. The compressor maintains a buffer
 * of twice of {@code windowSize} - real world values are in the area of 32k.</dd>
 *
 * <dt>{@code minBackReferenceLength}</dt>
 * <dd>Minimal length of a back-reference found. A true minimum of 3 is hard-coded inside of this implementation but bigger lengths can be configured.</dd>
 *
 * <dt>{@code maxBackReferenceLength}</dt>
 * <dd>Maximal length of a back-reference found.</dd>
 *
 * <dt>{@code maxOffset}</dt>
 * <dd>Maximal offset of a back-reference.</dd>
 *
 * <dt>{@code maxLiteralLength}</dt>
 * <dd>Maximal length of a literal block.</dd>
 * </dl>
 *
 * @see "https://tools.ietf.org/html/rfc1951#section-4"
 * @since 1.14
 * @NotThreadSafe
 */
public class LZ77Compressor {

    /**
     * Represents a back-reference.
     *
     * @since 1.28.0
     */
    public abstract static class AbstractReference extends Block {

        private final int offset;
        private final int length;

        /**
         * Constructs a new instance.
         *
         * @param blockType The block type.
         * @param offset the offset of the reference.
         * @param length the offset of the reference.
         */
        public AbstractReference(final BlockType blockType, final int offset, final int length) {
            super(blockType);
            this.offset = offset;
            this.length = length;
        }

        /**
         * Gets the offset of the reference.
         *
         * @return the length
         */
        public int getLength() {
            return length;
        }

        /**
         * Gets the offset of the reference.
         *
         * @return the offset
         */
        public int getOffset() {
            return offset;
        }

        @Override
        public String toString() {
            return super.toString() + " with offset " + offset + " and length " + length;
        }
    }

    /**
     * Represents a back-reference.
     */
    public static final class BackReference extends AbstractReference {

        /**
         * Constructs a new instance.
         *
         * @param offset the offset of the back-reference.
         * @param length the offset of the back-reference.
         */
        public BackReference(final int offset, final int length) {
            super(BlockType.BACK_REFERENCE, offset, length);
        }

    }

    /**
     * Base class representing blocks the compressor may emit.
     *
     * <p>
     * This class is not supposed to be subclassed by classes outside of Commons Compress so it is considered internal and changed that would break subclasses
     * may get introduced with future releases.
     * </p>
     */
    public abstract static class Block {

        /**
         * Enumerates the block types the compressor emits.
         */
        public enum BlockType {

            /**
             * The literal block type.
             */
            LITERAL,

            /**
             * The back-reference block type.
             */
            BACK_REFERENCE,

            /**
             * The end-of-data block type.
             */
            EOD
        }

        private final BlockType type;

        /**
         * Constructs a new typeless instance.
         *
         * @deprecated Use {@link #Block()}.
         */
        @Deprecated
        public Block() {
            this.type = null;
        }

        /**
         * Constructs a new instance.
         *
         * @param type the block type, may not be {@code null}.
         */
        protected Block(final BlockType type) {
            this.type = Objects.requireNonNull(type);
        }

        /**
         * Gets the the block type.
         *
         * @return the the block type.
         */
        public BlockType getType() {
            return type;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " " + getType();
        }
    }

    /**
     * Callback invoked while the compressor processes data.
     *
     * <p>
     * The callback is invoked on the same thread that receives the bytes to compress and may be invoked multiple times during the execution of
     * {@link #compress} or {@link #finish}.
     * </p>
     */
    public interface Callback {

        /**
         * Consumes a block.
         *
         * @param b the block to consume
         * @throws IOException in case of an error
         */
        void accept(Block b) throws IOException;
    }

    /** A simple "we are done" marker. */
    public static final class EOD extends Block {

        /**
         * The singleton instance.
         */
        private static final EOD INSTANCE = new EOD();

        /**
         * Constructs a new instance.
         */
        public EOD() {
            super(BlockType.EOD);
        }

    }

    /**
     * Represents a literal block of data.
     *
     * <p>
     * For performance reasons this encapsulates the real data, not a copy of it. Don't modify the data and process it inside of {@link Callback#accept}
     * immediately as it will get overwritten sooner or later.
     * </p>
     */
    public static final class LiteralBlock extends AbstractReference {

        private final byte[] data;

        /**
         * Constructs a new instance.
         *
         * @param data the literal data.
         * @param offset the length of literal block.
         * @param length the length of literal block.
         */
        public LiteralBlock(final byte[] data, final int offset, final int length) {
            super(BlockType.LITERAL, offset, length);
            this.data = data;
        }

        /**
         * Gets the literal data.
         *
         * <p>
         * This returns a live view of the actual data in order to avoid copying, modify the array at your own risk.
         * </p>
         *
         * @return the data
         */
        public byte[] getData() {
            return data;
        }

    }

    static final int NUMBER_OF_BYTES_IN_HASH = 3;
    private static final int NO_MATCH = -1;

    // we use a 15 bit hash code as calculated in updateHash
    private static final int HASH_SIZE = 1 << 15;
    private static final int HASH_MASK = HASH_SIZE - 1;

    private static final int H_SHIFT = 5;
    private final Parameters params;
    private final Callback callback;

    // the sliding window, twice as big as "windowSize" parameter
    private final byte[] window;

    // the head of hash-chain - indexed by hash-code, points to the
    // location inside of window of the latest sequence of bytes with
    // the given hash.
    private final int[] head;
    // for each window-location points to the latest earlier location
    // with the same hash. Only stores values for the latest
    // "windowSize" elements, the index is "window location modulo
    // windowSize".
    private final int[] prev;
    // bit mask used when indexing into prev
    private final int wMask;
    private boolean initialized;
    // the position inside of window that shall be encoded right now
    private int currentPosition;
    // the number of bytes available to compress including the one at
    // currentPosition
    private int lookahead;
    // the hash of the three bytes stating at the current position
    private int insertHash;

    // the position inside the window where the current literal
    // block starts (in case we are inside a literal block).
    private int blockStart;

    // position of the current match
    private int matchStart = NO_MATCH;

    // number of missed insertString calls for the up to three last
    // bytes of the last match that can only be performed once more
    // data has been read
    private int missedInserts;

    /**
     * Initializes a compressor with parameters and a callback.
     *
     * @param params   the parameters
     * @param callback the callback
     * @throws NullPointerException if either parameter is {@code null}
     */
    public LZ77Compressor(final Parameters params, final Callback callback) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(callback, "callback");

        this.params = params;
        this.callback = callback;

        final int wSize = params.getWindowSize();
        window = new byte[wSize * 2];
        wMask = wSize - 1;
        head = ArrayFill.fill(new int[HASH_SIZE], NO_MATCH);
        prev = new int[wSize];
    }

    private void catchUpMissedInserts() {
        while (missedInserts > 0) {
            insertString(currentPosition - missedInserts--);
        }
    }

    private void compress() throws IOException {
        final int minMatch = params.getMinBackReferenceLength();
        final boolean lazy = params.getLazyMatching();
        final int lazyThreshold = params.getLazyMatchingThreshold();

        while (lookahead >= minMatch) {
            catchUpMissedInserts();
            int matchLength = 0;
            final int hashHead = insertString(currentPosition);
            if (hashHead != NO_MATCH && hashHead - currentPosition <= params.getMaxOffset()) {
                // sets matchStart as a side effect
                matchLength = longestMatch(hashHead);

                if (lazy && matchLength <= lazyThreshold && lookahead > minMatch) {
                    // try to find a longer match using the next position
                    matchLength = longestMatchForNextPosition(matchLength);
                }
            }
            if (matchLength >= minMatch) {
                if (blockStart != currentPosition) {
                    // emit preceding literal block
                    flushLiteralBlock();
                    blockStart = NO_MATCH;
                }
                flushBackReference(matchLength);
                insertStringsInMatch(matchLength);
                lookahead -= matchLength;
                currentPosition += matchLength;
                blockStart = currentPosition;
            } else {
                // no match, append to current or start a new literal
                lookahead--;
                currentPosition++;
                if (currentPosition - blockStart >= params.getMaxLiteralLength()) {
                    flushLiteralBlock();
                    blockStart = currentPosition;
                }
            }
        }
    }

    /**
     * Feeds bytes into the compressor which in turn may emit zero or more blocks to the callback during the execution of this method.
     *
     * @param data the data to compress - must not be null
     * @throws IOException if the callback throws an exception
     */
    public void compress(final byte[] data) throws IOException {
        compress(data, 0, data.length);
    }

    /**
     * Feeds bytes into the compressor which in turn may emit zero or more blocks to the callback during the execution of this method.
     *
     * @param data the data to compress - must not be null
     * @param off  the start offset of the data
     * @param len  the number of bytes to compress
     * @throws IOException if the callback throws an exception
     */
    public void compress(final byte[] data, int off, int len) throws IOException {
        final int wSize = params.getWindowSize();
        while (len > wSize) { // chop into windowSize sized chunks
            doCompress(data, off, wSize);
            off += wSize;
            len -= wSize;
        }
        if (len > 0) {
            doCompress(data, off, len);
        }
    }

    // performs the actual algorithm with the pre-condition len <= windowSize
    private void doCompress(final byte[] data, final int off, final int len) throws IOException {
        final int spaceLeft = window.length - currentPosition - lookahead;
        if (len > spaceLeft) {
            slide();
        }
        System.arraycopy(data, off, window, currentPosition + lookahead, len);
        lookahead += len;
        if (!initialized && lookahead >= params.getMinBackReferenceLength()) {
            initialize();
        }
        if (initialized) {
            compress();
        }
    }

    /**
     * Tells the compressor to process all remaining data and signal end of data to the callback.
     *
     * <p>
     * The compressor will in turn emit at least one block ({@link EOD}) but potentially multiple blocks to the callback during the execution of this method.
     * </p>
     *
     * @throws IOException if the callback throws an exception
     */
    public void finish() throws IOException {
        if (blockStart != currentPosition || lookahead > 0) {
            currentPosition += lookahead;
            flushLiteralBlock();
        }
        callback.accept(EOD.INSTANCE);
    }

    private void flushBackReference(final int matchLength) throws IOException {
        callback.accept(new BackReference(currentPosition - matchStart, matchLength));
    }

    private void flushLiteralBlock() throws IOException {
        callback.accept(new LiteralBlock(window, blockStart, currentPosition - blockStart));
    }

    private void initialize() {
        for (int i = 0; i < NUMBER_OF_BYTES_IN_HASH - 1; i++) {
            insertHash = nextHash(insertHash, window[i]);
        }
        initialized = true;
    }

    /**
     * Inserts the current three byte sequence into the dictionary and returns the previous head of the hash-chain.
     *
     * <p>
     * Updates {@code insertHash} and {@code prev} as a side effect.
     * </p>
     */
    private int insertString(final int pos) {
        insertHash = nextHash(insertHash, window[pos - 1 + NUMBER_OF_BYTES_IN_HASH]);
        final int hashHead = head[insertHash];
        prev[pos & wMask] = hashHead;
        head[insertHash] = pos;
        return hashHead;
    }

    private void insertStringsInMatch(final int matchLength) {
        // inserts strings contained in current match
        // insertString inserts the byte 2 bytes after position, which may not yet be available -> missedInserts
        final int stop = Math.min(matchLength - 1, lookahead - NUMBER_OF_BYTES_IN_HASH);
        // currentPosition has been inserted already
        for (int i = 1; i <= stop; i++) {
            insertString(currentPosition + i);
        }
        missedInserts = matchLength - stop - 1;
    }

    /**
     * Searches the hash chain for real matches and returns the length of the longest match (0 if none were found) that isn't too far away (WRT maxOffset).
     *
     * <p>
     * Sets matchStart to the index of the start position of the longest match as a side effect.
     * </p>
     */
    private int longestMatch(int matchHead) {
        final int minLength = params.getMinBackReferenceLength();
        int longestMatchLength = minLength - 1;
        final int maxPossibleLength = Math.min(params.getMaxBackReferenceLength(), lookahead);
        final int minIndex = Math.max(0, currentPosition - params.getMaxOffset());
        final int niceBackReferenceLength = Math.min(maxPossibleLength, params.getNiceBackReferenceLength());
        final int maxCandidates = params.getMaxCandidates();
        for (int candidates = 0; candidates < maxCandidates && matchHead >= minIndex; candidates++) {
            int currentLength = 0;
            for (int i = 0; i < maxPossibleLength; i++) {
                if (window[matchHead + i] != window[currentPosition + i]) {
                    break;
                }
                currentLength++;
            }
            if (currentLength > longestMatchLength) {
                longestMatchLength = currentLength;
                matchStart = matchHead;
                if (currentLength >= niceBackReferenceLength) {
                    // no need to search any further
                    break;
                }
            }
            matchHead = prev[matchHead & wMask];
        }
        return longestMatchLength; // < minLength if no matches have been found, will be ignored in compress()
    }

    private int longestMatchForNextPosition(final int prevMatchLength) {
        // save a bunch of values to restore them if the next match isn't better than the current one
        final int prevMatchStart = matchStart;
        final int prevInsertHash = insertHash;

        lookahead--;
        currentPosition++;
        final int hashHead = insertString(currentPosition);
        final int prevHashHead = prev[currentPosition & wMask];
        int matchLength = longestMatch(hashHead);

        if (matchLength <= prevMatchLength) {
            // use the first match, as the next one isn't any better
            matchLength = prevMatchLength;
            matchStart = prevMatchStart;

            // restore modified values
            head[insertHash] = prevHashHead;
            insertHash = prevInsertHash;
            currentPosition--;
            lookahead++;
        }
        return matchLength;
    }

    /**
     * Assumes we are calculating the hash for three consecutive bytes as a rolling hash, i.e. for bytes ABCD if H is the hash of ABC the new hash for BCD is
     * nextHash(H, D).
     *
     * <p>
     * The hash is shifted by five bits on each update so all effects of A have been swapped after the third update.
     * </p>
     */
    private int nextHash(final int oldHash, final byte nextByte) {
        final int nextVal = nextByte & 0xFF;
        return (oldHash << H_SHIFT ^ nextVal) & HASH_MASK;
    }

    /**
     * Adds some initial data to fill the window with.
     *
     * <p>
     * This is used if the stream has been cut into blocks and back-references of one block may refer to data of the previous block(s). One such example is the
     * LZ4 frame format using block dependency.
     * </p>
     *
     * @param data the data to fill the window with.
     * @throws IllegalStateException if the compressor has already started to accept data
     */
    public void prefill(final byte[] data) {
        if (currentPosition != 0 || lookahead != 0) {
            throw new IllegalStateException("The compressor has already started to accept data, can't prefill anymore");
        }

        // don't need more than windowSize for back-references
        final int len = Math.min(params.getWindowSize(), data.length);
        System.arraycopy(data, data.length - len, window, 0, len);

        if (len >= NUMBER_OF_BYTES_IN_HASH) {
            initialize();
            final int stop = len - NUMBER_OF_BYTES_IN_HASH + 1;
            for (int i = 0; i < stop; i++) {
                insertString(i);
            }
            missedInserts = NUMBER_OF_BYTES_IN_HASH - 1;
        } else { // not enough data to hash anything
            missedInserts = len;
        }
        blockStart = currentPosition = len;
    }

    private void slide() throws IOException {
        final int wSize = params.getWindowSize();
        if (blockStart != currentPosition && blockStart < wSize) {
            flushLiteralBlock();
            blockStart = currentPosition;
        }
        System.arraycopy(window, wSize, window, 0, wSize);
        currentPosition -= wSize;
        matchStart -= wSize;
        blockStart -= wSize;
        for (int i = 0; i < HASH_SIZE; i++) {
            final int h = head[i];
            head[i] = h >= wSize ? h - wSize : NO_MATCH;
        }
        for (int i = 0; i < wSize; i++) {
            final int p = prev[i];
            prev[i] = p >= wSize ? p - wSize : NO_MATCH;
        }
    }
}
