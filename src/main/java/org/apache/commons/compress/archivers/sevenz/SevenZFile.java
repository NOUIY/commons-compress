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
package org.apache.commons.compress.archivers.sevenz;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

import org.apache.commons.compress.MemoryLimitException;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.utils.ByteUtils;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.InputStreamStatistics;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.build.AbstractOrigin.ByteArrayOrigin;
import org.apache.commons.io.build.AbstractStreamBuilder;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.io.input.ChecksumInputStream;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Reads a 7z file, using SeekableByteChannel under the covers.
 * <p>
 * The 7z file format is a flexible container that can contain many compression and encryption types, but at the moment only only Copy, LZMA, LZMA2, BZIP2,
 * Deflate and AES-256 + SHA-256 are supported.
 * </p>
 * <p>
 * The format is very Windows/Intel specific, so it uses little-endian byte order, doesn't store user/group or permission bits, and represents times using NTFS
 * timestamps (100 nanosecond units since 1 January 1601). Hence the official tools recommend against using it for backup purposes on *nix, and recommend
 * .tar.7z or .tar.lzma or .tar.xz instead.
 * </p>
 * <p>
 * Both the header and file contents may be compressed and/or encrypted. With both encrypted, neither file names nor file contents can be read, but the use of
 * encryption isn't plausibly deniable.
 * </p>
 * <p>
 * Multi volume archives can be read by concatenating the parts in correct order - either manually or by using {link
 * org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel} for example.
 * </p>
 *
 * @NotThreadSafe
 * @since 1.6
 */
public class SevenZFile implements Closeable {

    private static final class ArchiveStatistics {
        private int numberOfPackedStreams;
        private long numberOfCoders;
        private long numberOfOutStreams;
        private long numberOfInStreams;
        private long numberOfUnpackSubStreams;
        private int numberOfFolders;
        private BitSet folderHasCrc;
        private int numberOfEntries;
        private int numberOfEntriesWithStream;

        /**
         * Asserts the validity of the given input.
         *
         * @param maxMemoryLimitKiB kibibytes (KiB) to test.
         * @throws IOException Thrown on basic assertion failure.
         */
        void assertValidity(final int maxMemoryLimitKiB) throws IOException {
            if (numberOfEntriesWithStream > 0 && numberOfFolders == 0) {
                throw new IOException("archive with entries but no folders");
            }
            if (numberOfEntriesWithStream > numberOfUnpackSubStreams) {
                throw new IOException("archive doesn't contain enough substreams for entries");
            }
            final long memoryNeededInKiB = kbToKiB(estimateSize());
            if (maxMemoryLimitKiB < memoryNeededInKiB) {
                throw new MemoryLimitException(memoryNeededInKiB, maxMemoryLimitKiB);
            }
        }

        private long bindPairSize() {
            return 16;
        }

        /**
         * Gets a size estimate in bytes.
         *
         * @return a size estimate in bytes.
         */
        private long coderSize() {
            return 2 /* methodId is between 1 and four bytes currently, COPY and LZMA2 are the most common with 1 */
                    + 16 + 4 /* properties, guess */
            ;
        }

        /**
         * Gets a size estimate in bytes.
         *
         * @return a size estimate in bytes.
         */
        private long entrySize() {
            return 100; /* real size depends on name length, everything without name is about 70 bytes */
        }

        /**
         * Gets a size estimate in bytes.
         *
         * @return a size estimate in bytes.
         */
        long estimateSize() {
            final long lowerBound = 16L * numberOfPackedStreams /* packSizes, packCrcs in Archive */
                    + numberOfPackedStreams / 8 /* packCrcsDefined in Archive */
                    + numberOfFolders * folderSize() /* folders in Archive */
                    + numberOfCoders * coderSize() /* coders in Folder */
                    + (numberOfOutStreams - numberOfFolders) * bindPairSize() /* bindPairs in Folder */
                    + 8L * (numberOfInStreams - numberOfOutStreams + numberOfFolders) /* packedStreams in Folder */
                    + 8L * numberOfOutStreams /* unpackSizes in Folder */
                    + numberOfEntries * entrySize() /* files in Archive */
                    + streamMapSize();
            return 2 * lowerBound /* conservative guess */;
        }

        private long folderSize() {
            return 30; /* nested arrays are accounted for separately */
        }

        private long streamMapSize() {
            return 8 * numberOfFolders /* folderFirstPackStreamIndex, folderFirstFileIndex */
                    + 8 * numberOfPackedStreams /* packStreamOffsets */
                    + 4 * numberOfEntries /* fileFolderIndex */
            ;
        }

        @Override
        public String toString() {
            return String.format("Archive with %,d entries in %,d folders, estimated size %,d KiB.", numberOfEntries, numberOfFolders, kbToKiB(estimateSize()));
        }
    }

    /**
     * Builds new instances of {@link SevenZFile}.
     *
     * @since 1.26.0
     */
    public static class Builder extends AbstractStreamBuilder<SevenZFile, Builder> {

        static final int MEMORY_LIMIT_KIB = Integer.MAX_VALUE;
        static final boolean USE_DEFAULTNAME_FOR_UNNAMED_ENTRIES = false;
        static final boolean TRY_TO_RECOVER_BROKEN_ARCHIVES = false;

        private SeekableByteChannel seekableByteChannel;
        private String defaultName = DEFAULT_FILE_NAME;
        private byte[] password;
        private int maxMemoryLimitKiB = MEMORY_LIMIT_KIB;
        private boolean useDefaultNameForUnnamedEntries = USE_DEFAULTNAME_FOR_UNNAMED_ENTRIES;
        private boolean tryToRecoverBrokenArchives = TRY_TO_RECOVER_BROKEN_ARCHIVES;

        /**
         * Builds a new {@link SevenZFile}.
         *
         * @throws IOException Thrown if an I/O error occurs.
         */
        @SuppressWarnings("resource") // Caller closes
        @Override
        public SevenZFile get() throws IOException {
            final SeekableByteChannel actualChannel;
            final String actualDescription;
            if (seekableByteChannel != null) {
                actualChannel = seekableByteChannel;
                actualDescription = defaultName;
            } else if (checkOrigin() instanceof ByteArrayOrigin) {
                actualChannel = new SeekableInMemoryByteChannel(checkOrigin().getByteArray());
                actualDescription = defaultName;
            } else {
                OpenOption[] openOptions = getOpenOptions();
                if (ArrayUtils.isEmpty(openOptions)) {
                    openOptions = new OpenOption[] { StandardOpenOption.READ };
                }
                final Path path = getPath();
                actualChannel = Files.newByteChannel(path, openOptions);
                actualDescription = path.toAbsolutePath().toString();
            }
            final boolean closeOnError = seekableByteChannel != null;
            return new SevenZFile(actualChannel, actualDescription, password, closeOnError, maxMemoryLimitKiB, useDefaultNameForUnnamedEntries,
                    tryToRecoverBrokenArchives);
        }

        /**
         * Sets the default name.
         *
         * @param defaultName the default name.
         * @return {@code this} instance.
         */
        public Builder setDefaultName(final String defaultName) {
            this.defaultName = defaultName;
            return this;
        }

        /**
         * Sets the maximum amount of memory in kilobytes to use for parsing the archive and during extraction.
         * <p>
         * Not all codecs honor this setting. Currently only LZMA and LZMA2 are supported.
         * </p>
         *
         * @param maxMemoryLimitKb the max memory limit in kilobytes.
         * @return {@code this} instance.
         */
        public Builder setMaxMemoryLimitKb(final int maxMemoryLimitKb) {
            this.maxMemoryLimitKiB = kbToKiB(maxMemoryLimitKb);
            return this;
        }

        /**
         * Sets the maximum amount of memory in kilobytes to use for parsing the archive and during extraction.
         * <p>
         * Not all codecs honor this setting. Currently only LZMA and LZMA2 are supported.
         * </p>
         *
         * @param maxMemoryLimitKiB the max memory limit in kibibytes.
         * @return {@code this} instance.
         * @since 1.28.0
         */
        public Builder setMaxMemoryLimitKiB(final int maxMemoryLimitKiB) {
            this.maxMemoryLimitKiB = maxMemoryLimitKiB;
            return this;
        }

        /**
         * Sets the password.
         *
         * @param password the password.
         * @return {@code this} instance.
         */
        public Builder setPassword(final byte[] password) {
            this.password = password != null ? password.clone() : null;
            return this;
        }

        /**
         * Sets the password.
         *
         * @param password the password.
         * @return {@code this} instance.
         */
        public Builder setPassword(final char[] password) {
            this.password = password != null ? AES256SHA256Decoder.utf16Decode(password.clone()) : null;
            return this;
        }

        /**
         * Sets the password.
         *
         * @param password the password.
         * @return {@code this} instance.
         */
        public Builder setPassword(final String password) {
            this.password = password != null ? AES256SHA256Decoder.utf16Decode(password.toCharArray()) : null;
            return this;
        }

        /**
         * Sets the input channel.
         *
         * @param seekableByteChannel the input channel.
         * @return {@code this} instance.
         */
        public Builder setSeekableByteChannel(final SeekableByteChannel seekableByteChannel) {
            this.seekableByteChannel = seekableByteChannel;
            return this;
        }

        /**
         * Sets whether {@link SevenZFile} will try to recover broken archives where the CRC of the file's metadata is 0.
         * <p>
         * This special kind of broken archive is encountered when mutli volume archives are closed prematurely. If you enable this option SevenZFile will trust
         * data that looks as if it could contain metadata of an archive and allocate big amounts of memory. It is strongly recommended to not enable this
         * option without setting {@link #setMaxMemoryLimitKb(int)} at the same time.
         * </p>
         *
         * @param tryToRecoverBrokenArchives whether {@link SevenZFile} will try to recover broken archives where the CRC of the file's metadata is 0.
         * @return {@code this} instance.
         */
        public Builder setTryToRecoverBrokenArchives(final boolean tryToRecoverBrokenArchives) {
            this.tryToRecoverBrokenArchives = tryToRecoverBrokenArchives;
            return this;
        }

        /**
         * Sets whether entries without a name should get their names set to the archive's default file name.
         *
         * @param useDefaultNameForUnnamedEntries whether entries without a name should get their names set to the archive's default file name.
         * @return {@code this} instance.
         */
        public Builder setUseDefaultNameForUnnamedEntries(final boolean useDefaultNameForUnnamedEntries) {
            this.useDefaultNameForUnnamedEntries = useDefaultNameForUnnamedEntries;
            return this;
        }

    }

    static final int SIGNATURE_HEADER_SIZE = 32;

    private static final String DEFAULT_FILE_NAME = "unknown archive";

    /** Shared with SevenZOutputFile and tests, neither mutates it. */
    static final byte[] sevenZSignature = { // NOSONAR
            (byte) '7', (byte) 'z', (byte) 0xBC, (byte) 0xAF, (byte) 0x27, (byte) 0x1C };

    /**
     * Throws IOException if the given value is not in {@code [0, Integer.MAX_VALUE]}.
     *
     * @param description A description for the IOException.
     * @param value The value to check.
     * @return The given value as an int.
     * @throws IOException Thrown if the given value is not in {@code [0, Integer.MAX_VALUE]}.
     */
    private static int assertFitsIntoNonNegativeInt(final String description, final long value) throws IOException {
        if (value > Integer.MAX_VALUE || value < 0) {
            throw new IOException(String.format("Cannot handle %s %,d", description, value));
        }
        return (int) value;
    }

    /**
     * Creates a new Builder.
     *
     * @return a new Builder.
     * @since 1.26.0
     */
    public static Builder builder() {
        return new Builder();
    }

    private static ByteBuffer checkEndOfFile(final ByteBuffer buf, final int expectRemaining) throws EOFException {
        final int remaining = buf.remaining();
        if (remaining < expectRemaining) {
            throw new EOFException(String.format("remaining %,d < expectRemaining %,d", remaining, expectRemaining));
        }
        return buf;
    }

    private static void get(final ByteBuffer buf, final byte[] to) throws EOFException {
        checkEndOfFile(buf, to.length).get(to);
    }

    private static char getChar(final ByteBuffer buf) throws EOFException {
        return checkEndOfFile(buf, Character.BYTES).getChar();
    }

    private static int getInt(final ByteBuffer buf) throws EOFException {
        return checkEndOfFile(buf, Integer.BYTES).getInt();
    }

    private static long getLong(final ByteBuffer buf) throws EOFException {
        return checkEndOfFile(buf, Long.BYTES).getLong();
    }

    /**
     * Gets the next unsigned byte as an int.
     *
     * @param buf the byte source.
     * @return the next unsigned byte as an int.
     * @throws EOFException Thrown if the given buffer doesn't have a remaining byte.
     */
    private static int getUnsignedByte(final ByteBuffer buf) throws EOFException {
        if (!buf.hasRemaining()) {
            throw new EOFException();
        }
        return buf.get() & 0xff;
    }

    private static int kbToKiB(final int kilobytes) {
        return kilobytes * 1000 / 1024;
    }

    private static long kbToKiB(final long kilobytes) {
        return kilobytes * 1000 / 1024;
    }

    /**
     * Checks if the signature matches what is expected for a 7z file.
     *
     * @param signature the bytes to check
     * @param length    the number of bytes to check
     * @return true, if this is the signature of a 7z archive.
     * @since 1.8
     */
    public static boolean matches(final byte[] signature, final int length) {
        if (length < sevenZSignature.length) {
            return false;
        }
        for (int i = 0; i < sevenZSignature.length; i++) {
            if (signature[i] != sevenZSignature[i]) {
                return false;
            }
        }
        return true;
    }

    private static SeekableByteChannel newByteChannel(final File file) throws IOException {
        return Files.newByteChannel(file.toPath(), EnumSet.of(StandardOpenOption.READ));
    }

    private static long readUint64(final ByteBuffer in) throws IOException {
        // long rather than int as it might get shifted beyond the range of an int
        final long firstByte = getUnsignedByte(in);
        int mask = 0x80;
        long value = 0;
        for (int i = 0; i < 8; i++) {
            if ((firstByte & mask) == 0) {
                return value | (firstByte & mask - 1) << 8 * i;
            }
            final long nextByte = getUnsignedByte(in);
            value |= nextByte << 8 * i;
            mask >>>= 1;
        }
        return value;
    }

    private static long skipBytesFully(final ByteBuffer input, long bytesToSkip) {
        if (bytesToSkip < 1) {
            return 0;
        }
        final int current = input.position();
        final int maxSkip = input.remaining();
        if (maxSkip < bytesToSkip) {
            bytesToSkip = maxSkip;
        }
        input.position(current + (int) bytesToSkip);
        return bytesToSkip;
    }

    private final String fileName;
    private SeekableByteChannel channel;
    private final Archive archive;
    private int currentEntryIndex = -1;
    private int currentFolderIndex = -1;
    private InputStream currentFolderInputStream;
    private byte[] password;
    private long compressedBytesReadFromCurrentEntry;
    private long uncompressedBytesReadFromCurrentEntry;
    private final ArrayList<InputStream> deferredBlockStreams = new ArrayList<>();
    private final int maxMemoryLimitKiB;
    private final boolean useDefaultNameForUnnamedEntries;
    private final boolean tryToRecoverBrokenArchives;

    /**
     * Reads a file as unencrypted 7z archive.
     *
     * @param fileName the file to read.
     * @throws IOException if reading the archive fails.
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final File fileName) throws IOException {
        this(fileName, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a file as 7z archive
     *
     * @param file     the file to read
     * @param password optional password if the archive is encrypted - the byte array is supposed to be the UTF16-LE encoded representation of the password.
     * @throws IOException if reading the archive fails
     * @deprecated Use {@link Builder#get()}.
     */
    @SuppressWarnings("resource") // caller closes
    @Deprecated
    public SevenZFile(final File file, final byte[] password) throws IOException {
        this(newByteChannel(file), file.getAbsolutePath(), password, true, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a file as 7z archive
     *
     * @param file     the file to read
     * @param password optional password if the archive is encrypted
     * @throws IOException if reading the archive fails
     * @since 1.17
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final File file, final char[] password) throws IOException {
        this(file, password, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a file as 7z archive with additional options.
     *
     * @param file     the file to read
     * @param password optional password if the archive is encrypted
     * @param options  the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @SuppressWarnings("resource") // caller closes
    @Deprecated
    public SevenZFile(final File file, final char[] password, final SevenZFileOptions options) throws IOException {
        this(newByteChannel(file), // NOSONAR
                file.getAbsolutePath(), AES256SHA256Decoder.utf16Decode(password), true, options);
    }

    /**
     * Reads a file as unencrypted 7z archive
     *
     * @param file    the file to read
     * @param options the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final File file, final SevenZFileOptions options) throws IOException {
        this(file, null, options);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel the channel to read
     * @throws IOException if reading the archive fails
     * @since 1.13
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel) throws IOException {
        this(channel, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param password optional password if the archive is encrypted - the byte array is supposed to be the UTF16-LE encoded representation of the password.
     * @throws IOException if reading the archive fails
     * @since 1.13
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final byte[] password) throws IOException {
        this(channel, DEFAULT_FILE_NAME, password);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param password optional password if the archive is encrypted
     * @throws IOException if reading the archive fails
     * @since 1.17
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final char[] password) throws IOException {
        this(channel, password, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive with additional options.
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param password optional password if the archive is encrypted
     * @param options  the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final char[] password, final SevenZFileOptions options) throws IOException {
        this(channel, DEFAULT_FILE_NAME, password, options);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive with additional options.
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel the channel to read
     * @param options the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final SevenZFileOptions options) throws IOException {
        this(channel, DEFAULT_FILE_NAME, null, options);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param fileName name of the archive - only used for error reporting
     * @throws IOException if reading the archive fails
     * @since 1.17
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final String fileName) throws IOException {
        this(channel, fileName, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param fileName name of the archive - only used for error reporting
     * @param password optional password if the archive is encrypted - the byte array is supposed to be the UTF16-LE encoded representation of the password.
     * @throws IOException if reading the archive fails
     * @since 1.13
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final String fileName, final byte[] password) throws IOException {
        this(channel, fileName, password, false, SevenZFileOptions.DEFAULT);
    }

    private SevenZFile(final SeekableByteChannel channel, final String fileName, final byte[] password, final boolean closeOnError, final int maxMemoryLimitKiB,
            final boolean useDefaultNameForUnnamedEntries, final boolean tryToRecoverBrokenArchives) throws IOException {
        boolean succeeded = false;
        this.channel = channel;
        this.fileName = fileName;
        this.maxMemoryLimitKiB = maxMemoryLimitKiB;
        this.useDefaultNameForUnnamedEntries = useDefaultNameForUnnamedEntries;
        this.tryToRecoverBrokenArchives = tryToRecoverBrokenArchives;
        try {
            archive = readHeaders(password);
            if (password != null) {
                this.password = Arrays.copyOf(password, password.length);
            } else {
                this.password = null;
            }
            succeeded = true;
        } catch (final ArithmeticException | IllegalArgumentException e) {
            throw new ArchiveException(e);
        } finally {
            if (!succeeded && closeOnError) {
                this.channel.close();
            }
        }
    }

    /**
     * Constructs a new instance.
     *
     * @param channel      the channel to read.
     * @param fileName     name of the archive - only used for error reporting.
     * @param password     optional password if the archive is encrypted.
     * @param closeOnError closes the channel on error.
     * @param options      options.
     * @throws IOException if reading the archive fails
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    private SevenZFile(final SeekableByteChannel channel, final String fileName, final byte[] password, final boolean closeOnError,
            final SevenZFileOptions options) throws IOException {
        this(channel, fileName, password, closeOnError, options.getMaxMemoryLimitInKb(), options.getUseDefaultNameForUnnamedEntries(),
                options.getTryToRecoverBrokenArchives());
    }

    /**
     * Reads a SeekableByteChannel as 7z archive
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param fileName name of the archive - only used for error reporting
     * @param password optional password if the archive is encrypted
     * @throws IOException if reading the archive fails
     * @since 1.17
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final String fileName, final char[] password) throws IOException {
        this(channel, fileName, password, SevenZFileOptions.DEFAULT);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive with additional options.
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param fileName name of the archive - only used for error reporting
     * @param password optional password if the archive is encrypted
     * @param options  the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final String fileName, final char[] password, final SevenZFileOptions options) throws IOException {
        this(channel, fileName, AES256SHA256Decoder.utf16Decode(password), false, options);
    }

    /**
     * Reads a SeekableByteChannel as 7z archive with additional options.
     * <p>
     * {@link org.apache.commons.compress.utils.SeekableInMemoryByteChannel} allows you to read from an in-memory archive.
     * </p>
     *
     * @param channel  the channel to read
     * @param fileName name of the archive - only used for error reporting
     * @param options  the options to apply
     * @throws IOException if reading the archive fails or the memory limit (if set) is too small
     * @since 1.19
     * @deprecated Use {@link Builder#get()}.
     */
    @Deprecated
    public SevenZFile(final SeekableByteChannel channel, final String fileName, final SevenZFileOptions options) throws IOException {
        this(channel, fileName, null, false, options);
    }

    private InputStream buildDecoderStack(final Folder folder, final long folderOffset, final int firstPackStreamIndex, final SevenZArchiveEntry entry)
            throws IOException {
        channel.position(folderOffset);
        InputStream inputStreamStack = new FilterInputStream(
                new BufferedInputStream(new BoundedSeekableByteChannelInputStream(channel, archive.packSizes[firstPackStreamIndex]))) {
            private void count(final int c) {
                compressedBytesReadFromCurrentEntry = Math.addExact(compressedBytesReadFromCurrentEntry, c);
            }

            @Override
            public int read() throws IOException {
                final int r = in.read();
                if (r >= 0) {
                    count(1);
                }
                return r;
            }

            @Override
            public int read(final byte[] b) throws IOException {
                return read(b, 0, b.length);
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                final int r = in.read(b, off, len);
                if (r >= 0) {
                    count(r);
                }
                return r;
            }
        };
        final LinkedList<SevenZMethodConfiguration> methods = new LinkedList<>();
        for (final Coder coder : folder.getOrderedCoders()) {
            if (coder.numInStreams != 1 || coder.numOutStreams != 1) {
                throw new IOException("Multi input/output stream coders are not yet supported");
            }
            final SevenZMethod method = SevenZMethod.byId(coder.decompressionMethodId);
            inputStreamStack = Coders.addDecoder(fileName, inputStreamStack, folder.getUnpackSizeForCoder(coder), coder, password, maxMemoryLimitKiB);
            methods.addFirst(new SevenZMethodConfiguration(method, Coders.findByMethod(method).getOptionsFromCoder(coder, inputStreamStack)));
        }
        entry.setContentMethods(methods);
        if (folder.hasCrc) {
            // @formatter:off
            return ChecksumInputStream.builder()
                    .setChecksum(new CRC32())
                    .setInputStream(inputStreamStack)
                    .setCountThreshold(folder.getUnpackSize())
                    .setExpectedChecksumValue(folder.crc)
                    .get();
            // @formatter:on
        }
        return inputStreamStack;
    }

    /**
     * Builds the decoding stream for the entry to be read. This method may be called from a random access(getInputStream) or sequential access(getNextEntry).
     * If this method is called from a random access, some entries may need to be skipped(we put them to the deferredBlockStreams and skip them when actually
     * needed to improve the performance)
     *
     * @param entryIndex     the index of the entry to be read
     * @param isRandomAccess is this called in a random access
     * @throws IOException if there are exceptions when reading the file
     */
    private void buildDecodingStream(final int entryIndex, final boolean isRandomAccess) throws IOException {
        if (archive.streamMap == null) {
            throw new IOException("Archive doesn't contain stream information to read entries");
        }
        final int folderIndex = archive.streamMap.fileFolderIndex[entryIndex];
        if (folderIndex < 0) {
            deferredBlockStreams.clear();
            // TODO: previously it'd return an empty stream?
            // new BoundedInputStream(new ByteArrayInputStream(ByteUtils.EMPTY_BYTE_ARRAY), 0);
            return;
        }
        final SevenZArchiveEntry file = archive.files[entryIndex];
        boolean isInSameFolder = false;
        if (currentFolderIndex == folderIndex) {
            // (COMPRESS-320).
            // The current entry is within the same (potentially opened) folder. The
            // previous stream has to be fully decoded before we can start reading
            // but don't do it eagerly -- if the user skips over the entire folder nothing
            // is effectively decompressed.
            if (entryIndex > 0) {
                file.setContentMethods(archive.files[entryIndex - 1].getContentMethods());
            }
            // if this is called in a random access, then the content methods of previous entry may be null
            // the content methods should be set to methods of the first entry as it must not be null,
            // and the content methods would only be set if the content methods was not set
            if (isRandomAccess && file.getContentMethods() == null) {
                final int folderFirstFileIndex = archive.streamMap.folderFirstFileIndex[folderIndex];
                final SevenZArchiveEntry folderFirstFile = archive.files[folderFirstFileIndex];
                file.setContentMethods(folderFirstFile.getContentMethods());
            }
            isInSameFolder = true;
        } else {
            currentFolderIndex = folderIndex;
            // We're opening a new folder. Discard any queued streams/ folder stream.
            reopenFolderInputStream(folderIndex, file);
        }
        boolean haveSkippedEntries = false;
        if (isRandomAccess) {
            // entries will only need to be skipped if it's a random access
            haveSkippedEntries = skipEntriesWhenNeeded(entryIndex, isInSameFolder, folderIndex);
        }
        if (isRandomAccess && currentEntryIndex == entryIndex && !haveSkippedEntries) {
            // we don't need to add another entry to the deferredBlockStreams when :
            // 1. If this method is called in a random access and the entry index
            // to be read equals to the current entry index, the input stream
            // has already been put in the deferredBlockStreams
            // 2. If this entry has not been read(which means no entries are skipped)
            return;
        }
        // @formatter:off
        InputStream fileStream = BoundedInputStream.builder()
                .setInputStream(currentFolderInputStream)
                .setMaxCount(file.getSize())
                .setPropagateClose(false)
                .get();
        // @formatter:on
        if (file.getHasCrc()) {
            // @formatter:off
            fileStream = ChecksumInputStream.builder()
                    .setChecksum(new CRC32())
                    .setInputStream(fileStream)
                    .setExpectedChecksumValue(file.getCrcValue())
                    .setCountThreshold(file.getSize())
                    .get();
            // @formatter:on
        }
        deferredBlockStreams.add(fileStream);
    }

    private void calculateStreamMap(final Archive archive) throws IOException {
        int nextFolderPackStreamIndex = 0;
        final int numFolders = ArrayUtils.getLength(archive.folders);
        final int[] folderFirstPackStreamIndex = new int[numFolders];
        for (int i = 0; i < numFolders; i++) {
            folderFirstPackStreamIndex[i] = nextFolderPackStreamIndex;
            nextFolderPackStreamIndex = Math.addExact(nextFolderPackStreamIndex, archive.folders[i].packedStreams.length);
        }
        long nextPackStreamOffset = 0;
        final int numPackSizes = archive.packSizes.length;
        final long[] packStreamOffsets = new long[numPackSizes];
        for (int i = 0; i < numPackSizes; i++) {
            packStreamOffsets[i] = nextPackStreamOffset;
            nextPackStreamOffset = Math.addExact(nextPackStreamOffset, archive.packSizes[i]);
        }
        final int[] folderFirstFileIndex = new int[numFolders];
        final int[] fileFolderIndex = new int[archive.files.length];
        int nextFolderIndex = 0;
        int nextFolderUnpackStreamIndex = 0;
        for (int i = 0; i < archive.files.length; i++) {
            if (archive.files[i].isEmptyStream() && nextFolderUnpackStreamIndex == 0) {
                fileFolderIndex[i] = -1;
                continue;
            }
            if (nextFolderUnpackStreamIndex == 0) {
                for (; nextFolderIndex < archive.folders.length; ++nextFolderIndex) {
                    folderFirstFileIndex[nextFolderIndex] = i;
                    if (archive.folders[nextFolderIndex].numUnpackSubStreams > 0) {
                        break;
                    }
                }
                if (nextFolderIndex >= archive.folders.length) {
                    throw new IOException("Too few folders in archive");
                }
            }
            fileFolderIndex[i] = nextFolderIndex;
            if (archive.files[i].isEmptyStream()) {
                continue;
            }
            ++nextFolderUnpackStreamIndex;
            if (nextFolderUnpackStreamIndex >= archive.folders[nextFolderIndex].numUnpackSubStreams) {
                ++nextFolderIndex;
                nextFolderUnpackStreamIndex = 0;
            }
        }
        archive.streamMap = new StreamMap(folderFirstPackStreamIndex, packStreamOffsets, folderFirstFileIndex, fileFolderIndex);
    }

    private void checkEntryIsInitialized(final Map<Integer, SevenZArchiveEntry> archiveEntries, final int index) {
        archiveEntries.computeIfAbsent(index, i -> new SevenZArchiveEntry());
    }

    /**
     * Closes the archive.
     *
     * @throws IOException if closing the file fails
     */
    @Override
    public void close() throws IOException {
        if (channel != null) {
            try {
                channel.close();
            } finally {
                channel = null;
                if (password != null) {
                    Arrays.fill(password, (byte) 0);
                }
                password = null;
            }
        }
    }

    private InputStream getCurrentStream() throws IOException {
        if (archive.files[currentEntryIndex].getSize() == 0) {
            return new ByteArrayInputStream(ByteUtils.EMPTY_BYTE_ARRAY);
        }
        if (deferredBlockStreams.isEmpty()) {
            throw new IllegalStateException("No current 7z entry (call getNextEntry() first).");
        }
        while (deferredBlockStreams.size() > 1) {
            // In solid compression mode we need to decompress all leading folder'
            // streams to get access to an entry. We defer this until really needed
            // so that entire blocks can be skipped without wasting time for decompression.
            try (InputStream stream = deferredBlockStreams.remove(0)) {
                org.apache.commons.io.IOUtils.skip(stream, Long.MAX_VALUE, org.apache.commons.io.IOUtils::byteArray);
            }
            compressedBytesReadFromCurrentEntry = 0;
        }
        return deferredBlockStreams.get(0);
    }

    /**
     * Gets a default file name from the archive name - if known.
     * <p>
     * This implements the same heuristics the 7z tools use. In 7z's case if an archive contains entries without a name - i.e.
     * {@link SevenZArchiveEntry#getName} returns {@code null} - then its command line and GUI tools will use this default name when extracting the entries.
     * </p>
     *
     * @return null if the name of the archive is unknown. Otherwise, if the name of the archive has got any extension, it is stripped and the remainder
     *         returned. Finally, if the name of the archive hasn't got any extension, then a {@code ~} character is appended to the archive name.
     * @since 1.19
     */
    public String getDefaultName() {
        if (DEFAULT_FILE_NAME.equals(fileName) || fileName == null) {
            return null;
        }
        final String lastSegment = new File(fileName).getName();
        final int dotPos = lastSegment.lastIndexOf(".");
        if (dotPos > 0) { // if the file starts with a dot then this is not an extension
            return lastSegment.substring(0, dotPos);
        }
        return lastSegment + "~";
    }

    /**
     * Gets a copy of meta-data of all archive entries.
     * <p>
     * This method only provides meta-data, the entries cannot be used to read the contents, you still need to process all entries in order using
     * {@link #getNextEntry} for that.
     * </p>
     * <p>
     * The content methods are only available for entries that have already been reached via {@link #getNextEntry}.
     * </p>
     *
     * @return a copy of meta-data of all archive entries.
     * @since 1.11
     */
    public Iterable<SevenZArchiveEntry> getEntries() {
        return new ArrayList<>(Arrays.asList(archive.files));
    }

    /**
     * Gets an InputStream for reading the contents of the given entry.
     * <p>
     * For archives using solid compression randomly accessing entries will be significantly slower than reading the archive sequentially.
     * </p>
     *
     * @param entry the entry to get the stream for.
     * @return a stream to read the entry from.
     * @throws IOException if unable to create an input stream from the entry
     * @since 1.20
     */
    public InputStream getInputStream(final SevenZArchiveEntry entry) throws IOException {
        int entryIndex = -1;
        for (int i = 0; i < archive.files.length; i++) {
            if (entry == archive.files[i]) {
                entryIndex = i;
                break;
            }
        }
        if (entryIndex < 0) {
            throw new IllegalArgumentException("Can not find " + entry.getName() + " in " + fileName);
        }
        buildDecodingStream(entryIndex, true);
        currentEntryIndex = entryIndex;
        currentFolderIndex = archive.streamMap.fileFolderIndex[entryIndex];
        return getCurrentStream();
    }

    /**
     * Gets the next Archive Entry in this archive.
     *
     * @return the next entry, or {@code null} if there are no more entries
     * @throws IOException if the next entry could not be read
     */
    public SevenZArchiveEntry getNextEntry() throws IOException {
        if (currentEntryIndex >= archive.files.length - 1) {
            return null;
        }
        ++currentEntryIndex;
        final SevenZArchiveEntry entry = archive.files[currentEntryIndex];
        if (entry.getName() == null && useDefaultNameForUnnamedEntries) {
            entry.setName(getDefaultName());
        }
        buildDecodingStream(currentEntryIndex, false);
        uncompressedBytesReadFromCurrentEntry = compressedBytesReadFromCurrentEntry = 0;
        return entry;
    }

    /**
     * Gets statistics for bytes read from the current entry.
     *
     * @return statistics for bytes read from the current entry
     * @since 1.17
     */
    public InputStreamStatistics getStatisticsForCurrentEntry() {
        return new InputStreamStatistics() {
            @Override
            public long getCompressedCount() {
                return compressedBytesReadFromCurrentEntry;
            }

            @Override
            public long getUncompressedCount() {
                return uncompressedBytesReadFromCurrentEntry;
            }
        };
    }

    /**
     * Tests if any data of current entry has been read or not. This is achieved by comparing the bytes remaining to read and the size of the file.
     *
     * @return true if any data of current entry has been read
     * @since 1.21
     */
    private boolean hasCurrentEntryBeenRead() {
        boolean hasCurrentEntryBeenRead = false;
        if (!deferredBlockStreams.isEmpty()) {
            @SuppressWarnings("resource")
            final InputStream currentEntryInputStream = deferredBlockStreams.get(deferredBlockStreams.size() - 1);
            // get the bytes remaining to read, and compare it with the size of
            // the file to figure out if the file has been read
            if (currentEntryInputStream instanceof ChecksumInputStream) {
                hasCurrentEntryBeenRead = ((ChecksumInputStream) currentEntryInputStream).getRemaining() != archive.files[currentEntryIndex].getSize();
            } else if (currentEntryInputStream instanceof BoundedInputStream) {
                hasCurrentEntryBeenRead = ((BoundedInputStream) currentEntryInputStream).getRemaining() != archive.files[currentEntryIndex].getSize();
            }
        }
        return hasCurrentEntryBeenRead;
    }

    private Archive initializeArchive(final StartHeader startHeader, final byte[] password, final boolean verifyCrc) throws IOException {
        assertFitsIntoNonNegativeInt("nextHeaderSize", startHeader.nextHeaderSize);
        final int nextHeaderSizeInt = (int) startHeader.nextHeaderSize;
        channel.position(SIGNATURE_HEADER_SIZE + startHeader.nextHeaderOffset);
        if (verifyCrc) {
            final long position = channel.position();
            final CheckedInputStream cis = new CheckedInputStream(Channels.newInputStream(channel), new CRC32());
            if (cis.skip(nextHeaderSizeInt) != nextHeaderSizeInt) {
                throw new IOException("Problem computing NextHeader CRC-32");
            }
            if (startHeader.nextHeaderCrc != cis.getChecksum().getValue()) {
                throw new IOException("NextHeader CRC-32 mismatch");
            }
            channel.position(position);
        }
        Archive archive = new Archive();
        ByteBuffer buf = ByteBuffer.allocate(nextHeaderSizeInt).order(ByteOrder.LITTLE_ENDIAN);
        readFully(buf);
        int nid = getUnsignedByte(buf);
        if (nid == NID.kEncodedHeader) {
            buf = readEncodedHeader(buf, archive, password);
            // Archive gets rebuilt with the new header
            archive = new Archive();
            nid = getUnsignedByte(buf);
        }
        if (nid != NID.kHeader) {
            throw new IOException("Broken or unsupported archive: no Header");
        }
        readHeader(buf, archive);
        archive.subStreamsInfo = null;
        return archive;
    }

    /**
     * Reads a byte of data.
     *
     * @return the byte read, or -1 if end of input is reached
     * @throws IOException if an I/O error has occurred
     */
    public int read() throws IOException {
        @SuppressWarnings("resource") // does not allocate
        final int b = getCurrentStream().read();
        if (b >= 0) {
            uncompressedBytesReadFromCurrentEntry++;
        }
        return b;
    }

    /**
     * Reads data into an array of bytes.
     *
     * @param b the array to write data to
     * @return the number of bytes read, or -1 if end of input is reached
     * @throws IOException if an I/O error has occurred
     */
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /**
     * Reads data into an array of bytes.
     *
     * @param b   the array to write data to
     * @param off offset into the buffer to start filling at
     * @param len of bytes to read
     * @return the number of bytes read, or -1 if end of input is reached
     * @throws IOException if an I/O error has occurred
     */
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        @SuppressWarnings("resource") // does not allocate
        final int current = getCurrentStream().read(b, off, len);
        if (current > 0) {
            uncompressedBytesReadFromCurrentEntry = Math.addExact(uncompressedBytesReadFromCurrentEntry, current);
        }
        return current;
    }

    private BitSet readAllOrBits(final ByteBuffer header, final int size) throws IOException {
        final int areAllDefined = getUnsignedByte(header);
        final BitSet bits;
        if (areAllDefined != 0) {
            bits = new BitSet(size);
            for (int i = 0; i < size; i++) {
                bits.set(i, true);
            }
        } else {
            bits = readBits(header, size);
        }
        return bits;
    }

    private void readArchiveProperties(final ByteBuffer input) throws IOException {
        // FIXME: the reference implementation just throws them away?
        long nid = readUint64(input);
        while (nid != NID.kEnd) {
            final long propertySize = readUint64(input);
            final byte[] property = new byte[(int) propertySize];
            get(input, property);
            nid = readUint64(input);
        }
    }

    private BitSet readBits(final ByteBuffer header, final int size) throws IOException {
        final BitSet bits = new BitSet(size);
        int mask = 0;
        int cache = 0;
        for (int i = 0; i < size; i++) {
            if (mask == 0) {
                mask = 0x80;
                cache = getUnsignedByte(header);
            }
            bits.set(i, (cache & mask) != 0);
            mask >>>= 1;
        }
        return bits;
    }

    private ByteBuffer readEncodedHeader(final ByteBuffer header, final Archive archive, final byte[] password) throws IOException {
        final int pos = header.position();
        final ArchiveStatistics stats = new ArchiveStatistics();
        sanityCheckStreamsInfo(header, stats);
        stats.assertValidity(maxMemoryLimitKiB);
        header.position(pos);
        readStreamsInfo(header, archive);
        if (ArrayUtils.isEmpty(archive.folders)) {
            throw new IOException("no folders, can't read encoded header");
        }
        if (ArrayUtils.isEmpty(archive.packSizes)) {
            throw new IOException("no packed streams, can't read encoded header");
        }
        // FIXME: merge with buildDecodingStream()/buildDecoderStack() at some stage?
        final Folder folder = archive.folders[0];
        final int firstPackStreamIndex = 0;
        final long folderOffset = SIGNATURE_HEADER_SIZE + archive.packPos + 0;
        channel.position(folderOffset);
        InputStream inputStreamStack = new BoundedSeekableByteChannelInputStream(channel, archive.packSizes[firstPackStreamIndex]);
        for (final Coder coder : folder.getOrderedCoders()) {
            if (coder.numInStreams != 1 || coder.numOutStreams != 1) {
                throw new IOException("Multi input/output stream coders are not yet supported");
            }
            inputStreamStack = Coders.addDecoder(fileName, inputStreamStack, // NOSONAR
                    folder.getUnpackSizeForCoder(coder), coder, password, maxMemoryLimitKiB);
        }
        if (folder.hasCrc) {
            // @formatter:off
            inputStreamStack = ChecksumInputStream.builder()
                    .setChecksum(new CRC32())
                    .setInputStream(inputStreamStack)
                    .setCountThreshold(folder.getUnpackSize())
                    .setExpectedChecksumValue(folder.crc)
                    .get();
            // @formatter:on
        }
        final int unpackSize = assertFitsIntoNonNegativeInt("unpackSize", folder.getUnpackSize());
        final byte[] nextHeader = IOUtils.readRange(inputStreamStack, unpackSize);
        if (nextHeader.length < unpackSize) {
            throw new IOException("premature end of stream");
        }
        inputStreamStack.close();
        return ByteBuffer.wrap(nextHeader).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void readFilesInfo(final ByteBuffer header, final Archive archive) throws IOException {
        final int numFilesInt = (int) readUint64(header);
        final Map<Integer, SevenZArchiveEntry> fileMap = new LinkedHashMap<>();
        BitSet isEmptyStream = null;
        BitSet isEmptyFile = null;
        BitSet isAnti = null;
        while (true) {
            final int propertyType = getUnsignedByte(header);
            if (propertyType == 0) {
                break;
            }
            final long size = readUint64(header);
            switch (propertyType) {
            case NID.kEmptyStream: {
                isEmptyStream = readBits(header, numFilesInt);
                break;
            }
            case NID.kEmptyFile: {
                isEmptyFile = readBits(header, ArchiveException.requireNonNull(isEmptyStream, () -> "isEmptyStream for " + archive).cardinality());
                break;
            }
            case NID.kAnti: {
                isAnti = readBits(header, ArchiveException.requireNonNull(isEmptyStream, () -> "isEmptyStream for " + archive).cardinality());
                break;
            }
            case NID.kName: {
                /* final int external = */ getUnsignedByte(header);
                final byte[] names = new byte[(int) (size - 1)];
                final int namesLength = names.length;
                get(header, names);
                int nextFile = 0;
                int nextName = 0;
                for (int i = 0; i < namesLength; i += 2) {
                    if (names[i] == 0 && names[i + 1] == 0) {
                        checkEntryIsInitialized(fileMap, nextFile);
                        fileMap.get(nextFile).setName(new String(names, nextName, i - nextName, UTF_16LE));
                        nextName = i + 2;
                        nextFile++;
                    }
                }
                if (nextName != namesLength || nextFile != numFilesInt) {
                    throw new IOException("Error parsing file names");
                }
                break;
            }
            case NID.kCTime: {
                final BitSet timesDefined = readAllOrBits(header, numFilesInt);
                /* final int external = */ getUnsignedByte(header);
                for (int i = 0; i < numFilesInt; i++) {
                    checkEntryIsInitialized(fileMap, i);
                    final SevenZArchiveEntry entryAtIndex = fileMap.get(i);
                    entryAtIndex.setHasCreationDate(timesDefined.get(i));
                    if (entryAtIndex.getHasCreationDate()) {
                        entryAtIndex.setCreationDate(getLong(header));
                    }
                }
                break;
            }
            case NID.kATime: {
                final BitSet timesDefined = readAllOrBits(header, numFilesInt);
                /* final int external = */ getUnsignedByte(header);
                for (int i = 0; i < numFilesInt; i++) {
                    checkEntryIsInitialized(fileMap, i);
                    final SevenZArchiveEntry entryAtIndex = fileMap.get(i);
                    entryAtIndex.setHasAccessDate(timesDefined.get(i));
                    if (entryAtIndex.getHasAccessDate()) {
                        entryAtIndex.setAccessDate(getLong(header));
                    }
                }
                break;
            }
            case NID.kMTime: {
                final BitSet timesDefined = readAllOrBits(header, numFilesInt);
                /* final int external = */ getUnsignedByte(header);
                for (int i = 0; i < numFilesInt; i++) {
                    checkEntryIsInitialized(fileMap, i);
                    final SevenZArchiveEntry entryAtIndex = fileMap.get(i);
                    entryAtIndex.setHasLastModifiedDate(timesDefined.get(i));
                    if (entryAtIndex.getHasLastModifiedDate()) {
                        entryAtIndex.setLastModifiedDate(getLong(header));
                    }
                }
                break;
            }
            case NID.kWinAttributes: {
                final BitSet attributesDefined = readAllOrBits(header, numFilesInt);
                /* final int external = */ getUnsignedByte(header);
                for (int i = 0; i < numFilesInt; i++) {
                    checkEntryIsInitialized(fileMap, i);
                    final SevenZArchiveEntry entryAtIndex = fileMap.get(i);
                    entryAtIndex.setHasWindowsAttributes(attributesDefined.get(i));
                    if (entryAtIndex.getHasWindowsAttributes()) {
                        entryAtIndex.setWindowsAttributes(getInt(header));
                    }
                }
                break;
            }
            case NID.kDummy: {
                // 7z 9.20 asserts the content is all zeros and ignores the property
                // Compress up to 1.8.1 would throw an exception, now we ignore it (see COMPRESS-287
                skipBytesFully(header, size);
                break;
            }
            default: {
                // Compress up to 1.8.1 would throw an exception, now we ignore it (see COMPRESS-287
                skipBytesFully(header, size);
                break;
            }
            }
        }
        int nonEmptyFileCounter = 0;
        int emptyFileCounter = 0;
        for (int i = 0; i < numFilesInt; i++) {
            final SevenZArchiveEntry entryAtIndex = fileMap.get(i);
            if (entryAtIndex == null) {
                continue;
            }
            entryAtIndex.setHasStream(isEmptyStream == null || !isEmptyStream.get(i));
            if (entryAtIndex.hasStream()) {
                if (archive.subStreamsInfo == null) {
                    throw new IOException("Archive contains file with streams but no subStreamsInfo");
                }
                entryAtIndex.setDirectory(false);
                entryAtIndex.setAntiItem(false);
                entryAtIndex.setHasCrc(archive.subStreamsInfo.hasCrc.get(nonEmptyFileCounter));
                entryAtIndex.setCrcValue(archive.subStreamsInfo.crcs[nonEmptyFileCounter]);
                entryAtIndex.setSize(archive.subStreamsInfo.unpackSizes[nonEmptyFileCounter]);
                if (entryAtIndex.getSize() < 0) {
                    throw new IOException("broken archive, entry with negative size");
                }
                ++nonEmptyFileCounter;
            } else {
                entryAtIndex.setDirectory(isEmptyFile == null || !isEmptyFile.get(emptyFileCounter));
                entryAtIndex.setAntiItem(isAnti != null && isAnti.get(emptyFileCounter));
                entryAtIndex.setHasCrc(false);
                entryAtIndex.setSize(0);
                ++emptyFileCounter;
            }
        }
        archive.files = fileMap.values().stream().filter(Objects::nonNull).toArray(SevenZArchiveEntry[]::new);
        calculateStreamMap(archive);
    }

    private Folder readFolder(final ByteBuffer header) throws IOException {
        final Folder folder = new Folder();

        final long numCoders = readUint64(header);
        final Coder[] coders = new Coder[(int) numCoders];
        long totalInStreams = 0;
        long totalOutStreams = 0;
        for (int i = 0; i < coders.length; i++) {
            final int bits = getUnsignedByte(header);
            final int idSize = bits & 0xf;
            final boolean isSimple = (bits & 0x10) == 0;
            final boolean hasAttributes = (bits & 0x20) != 0;
            final boolean moreAlternativeMethods = (bits & 0x80) != 0;

            final byte[] decompressionMethodId = new byte[idSize];
            get(header, decompressionMethodId);
            final long numInStreams;
            final long numOutStreams;
            if (isSimple) {
                numInStreams = 1;
                numOutStreams = 1;
            } else {
                numInStreams = readUint64(header);
                numOutStreams = readUint64(header);
            }
            totalInStreams = Math.addExact(totalInStreams, numInStreams);
            totalOutStreams = Math.addExact(totalOutStreams, numOutStreams);
            byte[] properties = null;
            if (hasAttributes) {
                final long propertiesSize = readUint64(header);
                properties = new byte[(int) propertiesSize];
                get(header, properties);
            }
            // would need to keep looping as above:
            if (moreAlternativeMethods) {
                throw new IOException("Alternative methods are unsupported, please report. " + // NOSONAR
                        "The reference implementation doesn't support them either.");
            }
            coders[i] = new Coder(decompressionMethodId, numInStreams, numOutStreams, properties);
        }
        folder.coders = coders;
        folder.totalInputStreams = totalInStreams;
        folder.totalOutputStreams = totalOutStreams;

        final long numBindPairs = totalOutStreams - 1;
        final BindPair[] bindPairs = new BindPair[(int) numBindPairs];
        for (int i = 0; i < bindPairs.length; i++) {
            bindPairs[i] = new BindPair(readUint64(header), readUint64(header));
        }
        folder.bindPairs = bindPairs;

        final long numPackedStreams = totalInStreams - numBindPairs;
        final long[] packedStreams = new long[(int) numPackedStreams];
        if (numPackedStreams == 1) {
            int i;
            for (i = 0; i < (int) totalInStreams; i++) {
                if (folder.findBindPairForInStream(i) < 0) {
                    break;
                }
            }
            packedStreams[0] = i;
        } else {
            for (int i = 0; i < (int) numPackedStreams; i++) {
                packedStreams[i] = readUint64(header);
            }
        }
        folder.packedStreams = packedStreams;

        return folder;
    }

    private void readFully(final ByteBuffer buf) throws IOException {
        buf.rewind();
        IOUtils.readFully(channel, buf);
        buf.flip();
    }

    private void readHeader(final ByteBuffer header, final Archive archive) throws IOException {
        final int pos = header.position();
        final ArchiveStatistics stats = sanityCheckAndCollectStatistics(header);
        stats.assertValidity(maxMemoryLimitKiB);
        header.position(pos);

        int nid = getUnsignedByte(header);

        if (nid == NID.kArchiveProperties) {
            readArchiveProperties(header);
            nid = getUnsignedByte(header);
        }

        if (nid == NID.kAdditionalStreamsInfo) {
            throw new IOException("Additional streams unsupported");
            // nid = getUnsignedByte(header);
        }

        if (nid == NID.kMainStreamsInfo) {
            readStreamsInfo(header, archive);
            nid = getUnsignedByte(header);
        }

        if (nid == NID.kFilesInfo) {
            readFilesInfo(header, archive);
            nid = getUnsignedByte(header);
        }
    }

    private Archive readHeaders(final byte[] password) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(12 /* signature + 2 bytes version + 4 bytes CRC */).order(ByteOrder.LITTLE_ENDIAN);
        readFully(buf);
        final byte[] signature = new byte[6];
        buf.get(signature);
        if (!Arrays.equals(signature, sevenZSignature)) {
            throw new IOException("Bad 7z signature");
        }
        // 7zFormat.txt has it wrong - it's first major then minor
        final byte archiveVersionMajor = buf.get();
        final byte archiveVersionMinor = buf.get();
        if (archiveVersionMajor != 0) {
            throw new IOException(String.format("Unsupported 7z version (%d,%d)", archiveVersionMajor, archiveVersionMinor));
        }
        boolean headerLooksValid = false; // See https://www.7-zip.org/recover.html - "There is no correct End Header at the end of archive"
        final long startHeaderCrc = 0xffffFFFFL & buf.getInt();
        if (startHeaderCrc == 0) {
            // This is an indication of a corrupt header - peek the next 20 bytes
            final long currentPosition = channel.position();
            final ByteBuffer peekBuf = ByteBuffer.allocate(20);
            readFully(peekBuf);
            channel.position(currentPosition);
            // Header invalid if all data is 0
            while (peekBuf.hasRemaining()) {
                if (peekBuf.get() != 0) {
                    headerLooksValid = true;
                    break;
                }
            }
        } else {
            headerLooksValid = true;
        }
        if (headerLooksValid) {
            return initializeArchive(readStartHeader(startHeaderCrc), password, true);
        }
        // No valid header found - probably first file of multipart archive was removed too early. Scan for end header.
        if (tryToRecoverBrokenArchives) {
            return tryToLocateEndHeader(password);
        }
        throw new IOException("archive seems to be invalid.\nYou may want to retry and enable the"
                + " tryToRecoverBrokenArchives if the archive could be a multi volume archive that has been closed prematurely.");
    }

    private void readPackInfo(final ByteBuffer header, final Archive archive) throws IOException {
        archive.packPos = readUint64(header);
        final int numPackStreamsInt = (int) readUint64(header);
        int nid = getUnsignedByte(header);
        if (nid == NID.kSize) {
            archive.packSizes = new long[numPackStreamsInt];
            for (int i = 0; i < archive.packSizes.length; i++) {
                archive.packSizes[i] = readUint64(header);
            }
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kCRC) {
            archive.packCrcsDefined = readAllOrBits(header, numPackStreamsInt);
            archive.packCrcs = new long[numPackStreamsInt];
            for (int i = 0; i < numPackStreamsInt; i++) {
                if (archive.packCrcsDefined.get(i)) {
                    archive.packCrcs[i] = 0xffffFFFFL & getInt(header);
                }
            }
            // read one more
            getUnsignedByte(header);
        }
    }

    private StartHeader readStartHeader(final long startHeaderCrc) throws IOException {
        // using Stream rather than ByteBuffer for the benefit of the built-in CRC check
        try (DataInputStream dataInputStream = new DataInputStream(ChecksumInputStream.builder()
                // @formatter:off
                .setChecksum(new CRC32())
                .setInputStream(new BoundedSeekableByteChannelInputStream(channel, 20))
                .setCountThreshold(20L)
                .setExpectedChecksumValue(startHeaderCrc)
                .get())) {
                // @formatter:on
            final long nextHeaderOffset = Long.reverseBytes(dataInputStream.readLong());
            if (nextHeaderOffset < 0 || nextHeaderOffset + SIGNATURE_HEADER_SIZE > channel.size()) {
                throw new IOException("nextHeaderOffset is out of bounds");
            }
            final long nextHeaderSize = Long.reverseBytes(dataInputStream.readLong());
            final long nextHeaderEnd = nextHeaderOffset + nextHeaderSize;
            if (nextHeaderEnd < nextHeaderOffset || nextHeaderEnd + SIGNATURE_HEADER_SIZE > channel.size()) {
                throw new IOException("nextHeaderSize is out of bounds");
            }
            final long nextHeaderCrc = 0xffffFFFFL & Integer.reverseBytes(dataInputStream.readInt());
            return new StartHeader(nextHeaderOffset, nextHeaderSize, nextHeaderCrc);
        }
    }

    private void readStreamsInfo(final ByteBuffer header, final Archive archive) throws IOException {
        int nid = getUnsignedByte(header);
        if (nid == NID.kPackInfo) {
            readPackInfo(header, archive);
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kUnpackInfo) {
            readUnpackInfo(header, archive);
            nid = getUnsignedByte(header);
        } else {
            // archive without unpack/coders info
            archive.folders = Folder.EMPTY_FOLDER_ARRAY;
        }
        if (nid == NID.kSubStreamsInfo) {
            readSubStreamsInfo(header, archive);
            nid = getUnsignedByte(header);
        }
    }

    private void readSubStreamsInfo(final ByteBuffer header, final Archive archive) throws IOException {
        for (final Folder folder : archive.folders) {
            folder.numUnpackSubStreams = 1;
        }
        long unpackStreamsCount = archive.folders.length;
        int nid = getUnsignedByte(header);
        if (nid == NID.kNumUnpackStream) {
            unpackStreamsCount = 0;
            for (final Folder folder : archive.folders) {
                final long numStreams = readUint64(header);
                folder.numUnpackSubStreams = (int) numStreams;
                unpackStreamsCount = Math.addExact(unpackStreamsCount, numStreams);
            }
            nid = getUnsignedByte(header);
        }
        final SubStreamsInfo subStreamsInfo = new SubStreamsInfo(unpackStreamsCount, maxMemoryLimitKiB);
        int nextUnpackStream = 0;
        for (final Folder folder : archive.folders) {
            if (folder.numUnpackSubStreams == 0) {
                continue;
            }
            long sum = 0;
            if (nid == NID.kSize) {
                for (int i = 0; i < folder.numUnpackSubStreams - 1; i++) {
                    final long size = readUint64(header);
                    subStreamsInfo.unpackSizes[nextUnpackStream++] = size;
                    sum = Math.addExact(sum, size);
                }
            }
            if (sum > folder.getUnpackSize()) {
                throw new IOException("sum of unpack sizes of folder exceeds total unpack size");
            }
            subStreamsInfo.unpackSizes[nextUnpackStream++] = folder.getUnpackSize() - sum;
        }
        if (nid == NID.kSize) {
            nid = getUnsignedByte(header);
        }
        int numDigests = 0;
        for (final Folder folder : archive.folders) {
            if (folder.numUnpackSubStreams != 1 || !folder.hasCrc) {
                numDigests = Math.addExact(numDigests, folder.numUnpackSubStreams);
            }
        }
        if (nid == NID.kCRC) {
            final BitSet hasMissingCrc = readAllOrBits(header, numDigests);
            final long[] missingCrcs = new long[numDigests];
            for (int i = 0; i < numDigests; i++) {
                if (hasMissingCrc.get(i)) {
                    missingCrcs[i] = 0xffffFFFFL & getInt(header);
                }
            }
            int nextCrc = 0;
            int nextMissingCrc = 0;
            for (final Folder folder : archive.folders) {
                if (folder.numUnpackSubStreams == 1 && folder.hasCrc) {
                    subStreamsInfo.hasCrc.set(nextCrc, true);
                    subStreamsInfo.crcs[nextCrc] = folder.crc;
                    ++nextCrc;
                } else {
                    for (int i = 0; i < folder.numUnpackSubStreams; i++) {
                        subStreamsInfo.hasCrc.set(nextCrc, hasMissingCrc.get(nextMissingCrc));
                        subStreamsInfo.crcs[nextCrc] = missingCrcs[nextMissingCrc];
                        ++nextCrc;
                        ++nextMissingCrc;
                    }
                }
            }
            nid = getUnsignedByte(header);
        }
        archive.subStreamsInfo = subStreamsInfo;
    }

    private void readUnpackInfo(final ByteBuffer header, final Archive archive) throws IOException {
        int nid = getUnsignedByte(header);
        final int numFoldersInt = (int) readUint64(header);
        final Folder[] folders = new Folder[numFoldersInt];
        archive.folders = folders;
        /* final int external = */ getUnsignedByte(header);
        for (int i = 0; i < numFoldersInt; i++) {
            folders[i] = readFolder(header);
        }
        nid = getUnsignedByte(header);
        for (final Folder folder : folders) {
            assertFitsIntoNonNegativeInt("totalOutputStreams", folder.totalOutputStreams);
            folder.unpackSizes = new long[(int) folder.totalOutputStreams];
            for (int i = 0; i < folder.totalOutputStreams; i++) {
                folder.unpackSizes[i] = readUint64(header);
            }
        }
        nid = getUnsignedByte(header);
        if (nid == NID.kCRC) {
            final BitSet crcsDefined = readAllOrBits(header, numFoldersInt);
            for (int i = 0; i < numFoldersInt; i++) {
                if (crcsDefined.get(i)) {
                    folders[i].hasCrc = true;
                    folders[i].crc = 0xffffFFFFL & getInt(header);
                } else {
                    folders[i].hasCrc = false;
                }
            }
            nid = getUnsignedByte(header);
        }
    }

    /**
     * Discard any queued streams/ folder stream, and reopen the current folder input stream.
     *
     * @param folderIndex the index of the folder to reopen
     * @param file        the 7z entry to read
     * @throws IOException if exceptions occur when reading the 7z file
     */
    private void reopenFolderInputStream(final int folderIndex, final SevenZArchiveEntry file) throws IOException {
        deferredBlockStreams.clear();
        if (currentFolderInputStream != null) {
            currentFolderInputStream.close();
            currentFolderInputStream = null;
        }
        final Folder folder = archive.folders[folderIndex];
        final int firstPackStreamIndex = archive.streamMap.folderFirstPackStreamIndex[folderIndex];
        final long folderOffset = SIGNATURE_HEADER_SIZE + archive.packPos + archive.streamMap.packStreamOffsets[firstPackStreamIndex];
        currentFolderInputStream = buildDecoderStack(folder, folderOffset, firstPackStreamIndex, file);
    }

    private ArchiveStatistics sanityCheckAndCollectStatistics(final ByteBuffer header) throws IOException {
        final ArchiveStatistics stats = new ArchiveStatistics();
        int nid = getUnsignedByte(header);
        if (nid == NID.kArchiveProperties) {
            sanityCheckArchiveProperties(header);
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kAdditionalStreamsInfo) {
            throw new IOException("Additional streams unsupported");
            // nid = getUnsignedByte(header);
        }
        if (nid == NID.kMainStreamsInfo) {
            sanityCheckStreamsInfo(header, stats);
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kFilesInfo) {
            sanityCheckFilesInfo(header, stats);
            nid = getUnsignedByte(header);
        }
        if (nid != NID.kEnd) {
            throw new IOException("Badly terminated header, found " + nid);
        }
        return stats;
    }

    private void sanityCheckArchiveProperties(final ByteBuffer header) throws IOException {
        long nid = readUint64(header);
        while (nid != NID.kEnd) {
            final int propertySize = assertFitsIntoNonNegativeInt("propertySize", readUint64(header));
            if (skipBytesFully(header, propertySize) < propertySize) {
                throw new IOException("invalid property size");
            }
            nid = readUint64(header);
        }
    }

    private void sanityCheckFilesInfo(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        stats.numberOfEntries = assertFitsIntoNonNegativeInt("numFiles", readUint64(header));
        int emptyStreams = -1;
        while (true) {
            final int propertyType = getUnsignedByte(header);
            if (propertyType == 0) {
                break;
            }
            final long size = readUint64(header);
            switch (propertyType) {
            case NID.kEmptyStream: {
                emptyStreams = readBits(header, stats.numberOfEntries).cardinality();
                break;
            }
            case NID.kEmptyFile: {
                if (emptyStreams == -1) {
                    throw new IOException("Header format error: kEmptyStream must appear before kEmptyFile");
                }
                readBits(header, emptyStreams);
                break;
            }
            case NID.kAnti: {
                if (emptyStreams == -1) {
                    throw new IOException("Header format error: kEmptyStream must appear before kAnti");
                }
                readBits(header, emptyStreams);
                break;
            }
            case NID.kName: {
                final int external = getUnsignedByte(header);
                if (external != 0) {
                    throw new IOException("Not implemented");
                }
                final int namesLength = assertFitsIntoNonNegativeInt("file names length", size - 1);
                if ((namesLength & 1) != 0) {
                    throw new IOException("File names length invalid");
                }
                int filesSeen = 0;
                for (int i = 0; i < namesLength; i += 2) {
                    final char c = getChar(header);
                    if (c == 0) {
                        filesSeen++;
                    }
                }
                if (filesSeen != stats.numberOfEntries) {
                    throw new IOException("Invalid number of file names (" + filesSeen + " instead of " + stats.numberOfEntries + ")");
                }
                break;
            }
            case NID.kCTime: {
                final int timesDefined = readAllOrBits(header, stats.numberOfEntries).cardinality();
                final int external = getUnsignedByte(header);
                if (external != 0) {
                    throw new IOException("Not implemented");
                }
                if (skipBytesFully(header, 8 * timesDefined) < 8 * timesDefined) {
                    throw new IOException("invalid creation dates size");
                }
                break;
            }
            case NID.kATime: {
                final int timesDefined = readAllOrBits(header, stats.numberOfEntries).cardinality();
                final int external = getUnsignedByte(header);
                if (external != 0) {
                    throw new IOException("Not implemented");
                }
                if (skipBytesFully(header, 8 * timesDefined) < 8 * timesDefined) {
                    throw new IOException("invalid access dates size");
                }
                break;
            }
            case NID.kMTime: {
                final int timesDefined = readAllOrBits(header, stats.numberOfEntries).cardinality();
                final int external = getUnsignedByte(header);
                if (external != 0) {
                    throw new IOException("Not implemented");
                }
                if (skipBytesFully(header, 8 * timesDefined) < 8 * timesDefined) {
                    throw new IOException("invalid modification dates size");
                }
                break;
            }
            case NID.kWinAttributes: {
                final int attributesDefined = readAllOrBits(header, stats.numberOfEntries).cardinality();
                final int external = getUnsignedByte(header);
                if (external != 0) {
                    throw new IOException("Not implemented");
                }
                if (skipBytesFully(header, 4 * attributesDefined) < 4 * attributesDefined) {
                    throw new IOException("invalid windows attributes size");
                }
                break;
            }
            case NID.kStartPos: {
                throw new IOException("kStartPos is unsupported, please report");
            }
            case NID.kDummy: {
                // 7z 9.20 asserts the content is all zeros and ignores the property
                // Compress up to 1.8.1 would throw an exception, now we ignore it (see COMPRESS-287
                if (skipBytesFully(header, size) < size) {
                    throw new IOException("Incomplete kDummy property");
                }
                break;
            }
            default: {
                // Compress up to 1.8.1 would throw an exception, now we ignore it (see COMPRESS-287
                if (skipBytesFully(header, size) < size) {
                    throw new IOException("Incomplete property of type " + propertyType);
                }
                break;
            }
            }
        }
        stats.numberOfEntriesWithStream = stats.numberOfEntries - Math.max(emptyStreams, 0);
    }

    private int sanityCheckFolder(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        final int numCoders = assertFitsIntoNonNegativeInt("numCoders", readUint64(header));
        if (numCoders == 0) {
            throw new IOException("Folder without coders");
        }
        stats.numberOfCoders = Math.addExact(stats.numberOfCoders, numCoders);
        long totalOutStreams = 0;
        long totalInStreams = 0;
        for (int i = 0; i < numCoders; i++) {
            final int bits = getUnsignedByte(header);
            final int idSize = bits & 0xf;
            get(header, new byte[idSize]);
            final boolean isSimple = (bits & 0x10) == 0;
            final boolean hasAttributes = (bits & 0x20) != 0;
            final boolean moreAlternativeMethods = (bits & 0x80) != 0;
            if (moreAlternativeMethods) {
                throw new IOException("Alternative methods are unsupported, please report. The reference implementation doesn't support them either.");
            }
            if (isSimple) {
                totalInStreams++;
                totalOutStreams++;
            } else {
                totalInStreams = Math.addExact(totalInStreams, assertFitsIntoNonNegativeInt("numInStreams", readUint64(header)));
                totalOutStreams = Math.addExact(totalOutStreams, assertFitsIntoNonNegativeInt("numOutStreams", readUint64(header)));
            }
            if (hasAttributes) {
                final int propertiesSize = assertFitsIntoNonNegativeInt("propertiesSize", readUint64(header));
                if (skipBytesFully(header, propertiesSize) < propertiesSize) {
                    throw new IOException("invalid propertiesSize in folder");
                }
            }
        }
        assertFitsIntoNonNegativeInt("totalInStreams", totalInStreams);
        assertFitsIntoNonNegativeInt("totalOutStreams", totalOutStreams);
        stats.numberOfOutStreams = Math.addExact(stats.numberOfOutStreams, totalOutStreams);
        stats.numberOfInStreams = Math.addExact(stats.numberOfInStreams, totalInStreams);
        if (totalOutStreams == 0) {
            throw new IOException("Total output streams can't be 0");
        }
        final int numBindPairs = assertFitsIntoNonNegativeInt("numBindPairs", totalOutStreams - 1);
        if (totalInStreams < numBindPairs) {
            throw new IOException("Total input streams can't be less than the number of bind pairs");
        }
        final BitSet inStreamsBound = new BitSet((int) totalInStreams);
        for (int i = 0; i < numBindPairs; i++) {
            final int inIndex = assertFitsIntoNonNegativeInt("inIndex", readUint64(header));
            if (totalInStreams <= inIndex) {
                throw new IOException("inIndex is bigger than number of inStreams");
            }
            inStreamsBound.set(inIndex);
            final int outIndex = assertFitsIntoNonNegativeInt("outIndex", readUint64(header));
            if (totalOutStreams <= outIndex) {
                throw new IOException("outIndex is bigger than number of outStreams");
            }
        }
        final int numPackedStreams = assertFitsIntoNonNegativeInt("numPackedStreams", totalInStreams - numBindPairs);
        if (numPackedStreams == 1) {
            if (inStreamsBound.nextClearBit(0) == -1) {
                throw new IOException("Couldn't find stream's bind pair index");
            }
        } else {
            for (int i = 0; i < numPackedStreams; i++) {
                final int packedStreamIndex = assertFitsIntoNonNegativeInt("packedStreamIndex", readUint64(header));
                if (packedStreamIndex >= totalInStreams) {
                    throw new IOException("packedStreamIndex is bigger than number of totalInStreams");
                }
            }
        }
        return (int) totalOutStreams;
    }

    private void sanityCheckPackInfo(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        final long packPos = readUint64(header);
        if (packPos < 0 || SIGNATURE_HEADER_SIZE + packPos > channel.size() || SIGNATURE_HEADER_SIZE + packPos < 0) {
            throw new IOException("packPos (" + packPos + ") is out of range");
        }
        final long numPackStreams = readUint64(header);
        stats.numberOfPackedStreams = assertFitsIntoNonNegativeInt("numPackStreams", numPackStreams);
        int nid = getUnsignedByte(header);
        if (nid == NID.kSize) {
            long totalPackSizes = 0;
            for (int i = 0; i < stats.numberOfPackedStreams; i++) {
                final long packSize = readUint64(header);
                totalPackSizes = Math.addExact(totalPackSizes, packSize);
                final long endOfPackStreams = SIGNATURE_HEADER_SIZE + packPos + totalPackSizes;
                if (packSize < 0 || endOfPackStreams > channel.size() || endOfPackStreams < packPos) {
                    throw new IOException("packSize (" + packSize + ") is out of range");
                }
            }
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kCRC) {
            final int crcsDefined = readAllOrBits(header, stats.numberOfPackedStreams).cardinality();
            if (skipBytesFully(header, 4 * crcsDefined) < 4 * crcsDefined) {
                throw new IOException("invalid number of CRCs in PackInfo");
            }
            nid = getUnsignedByte(header);
        }
        if (nid != NID.kEnd) {
            throw new IOException("Badly terminated PackInfo (" + nid + ")");
        }
    }

    private void sanityCheckStreamsInfo(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        int nid = getUnsignedByte(header);
        if (nid == NID.kPackInfo) {
            sanityCheckPackInfo(header, stats);
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kUnpackInfo) {
            sanityCheckUnpackInfo(header, stats);
            nid = getUnsignedByte(header);
        }
        if (nid == NID.kSubStreamsInfo) {
            sanityCheckSubStreamsInfo(header, stats);
            nid = getUnsignedByte(header);
        }
        if (nid != NID.kEnd) {
            throw new IOException("Badly terminated StreamsInfo");
        }
    }

    private void sanityCheckSubStreamsInfo(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        int nid = getUnsignedByte(header);
        final List<Integer> numUnpackSubStreamsPerFolder = new LinkedList<>();
        if (nid == NID.kNumUnpackStream) {
            for (int i = 0; i < stats.numberOfFolders; i++) {
                numUnpackSubStreamsPerFolder.add(assertFitsIntoNonNegativeInt("numStreams", readUint64(header)));
            }
            stats.numberOfUnpackSubStreams = numUnpackSubStreamsPerFolder.stream().mapToLong(Integer::longValue).sum();
            nid = getUnsignedByte(header);
        } else {
            stats.numberOfUnpackSubStreams = stats.numberOfFolders;
        }
        assertFitsIntoNonNegativeInt("totalUnpackStreams", stats.numberOfUnpackSubStreams);
        if (nid == NID.kSize) {
            for (final int numUnpackSubStreams : numUnpackSubStreamsPerFolder) {
                if (numUnpackSubStreams == 0) {
                    continue;
                }
                for (int i = 0; i < numUnpackSubStreams - 1; i++) {
                    final long size = readUint64(header);
                    if (size < 0) {
                        throw new IOException("negative unpackSize");
                    }
                }
            }
            nid = getUnsignedByte(header);
        }
        int numDigests = 0;
        if (numUnpackSubStreamsPerFolder.isEmpty()) {
            numDigests = stats.folderHasCrc == null ? stats.numberOfFolders : stats.numberOfFolders - stats.folderHasCrc.cardinality();
        } else {
            int folderIdx = 0;
            for (final int numUnpackSubStreams : numUnpackSubStreamsPerFolder) {
                if (numUnpackSubStreams != 1 || stats.folderHasCrc == null || !stats.folderHasCrc.get(folderIdx++)) {
                    numDigests = Math.addExact(numDigests, numUnpackSubStreams);
                }
            }
        }
        if (nid == NID.kCRC) {
            assertFitsIntoNonNegativeInt("numDigests", numDigests);
            final int missingCrcs = readAllOrBits(header, numDigests).cardinality();
            if (skipBytesFully(header, 4 * missingCrcs) < 4 * missingCrcs) {
                throw new IOException("invalid number of missing CRCs in SubStreamInfo");
            }
            nid = getUnsignedByte(header);
        }
        if (nid != NID.kEnd) {
            throw new IOException("Badly terminated SubStreamsInfo");
        }
    }

    private void sanityCheckUnpackInfo(final ByteBuffer header, final ArchiveStatistics stats) throws IOException {
        int nid = getUnsignedByte(header);
        if (nid != NID.kFolder) {
            throw new IOException("Expected kFolder, got " + nid);
        }
        final long numFolders = readUint64(header);
        stats.numberOfFolders = assertFitsIntoNonNegativeInt("numFolders", numFolders);
        final int external = getUnsignedByte(header);
        if (external != 0) {
            throw new IOException("External unsupported");
        }
        final List<Integer> numberOfOutputStreamsPerFolder = new LinkedList<>();
        for (int i = 0; i < stats.numberOfFolders; i++) {
            numberOfOutputStreamsPerFolder.add(sanityCheckFolder(header, stats));
        }
        final long totalNumberOfBindPairs = stats.numberOfOutStreams - stats.numberOfFolders;
        final long packedStreamsRequiredByFolders = stats.numberOfInStreams - totalNumberOfBindPairs;
        if (packedStreamsRequiredByFolders < stats.numberOfPackedStreams) {
            throw new IOException("archive doesn't contain enough packed streams");
        }
        nid = getUnsignedByte(header);
        if (nid != NID.kCodersUnpackSize) {
            throw new IOException("Expected kCodersUnpackSize, got " + nid);
        }
        for (final int numberOfOutputStreams : numberOfOutputStreamsPerFolder) {
            for (int i = 0; i < numberOfOutputStreams; i++) {
                final long unpackSize = readUint64(header);
                if (unpackSize < 0) {
                    throw new IllegalArgumentException("negative unpackSize");
                }
            }
        }
        nid = getUnsignedByte(header);
        if (nid == NID.kCRC) {
            stats.folderHasCrc = readAllOrBits(header, stats.numberOfFolders);
            final int crcsDefined = stats.folderHasCrc.cardinality();
            if (skipBytesFully(header, 4 * crcsDefined) < 4 * crcsDefined) {
                throw new IOException("invalid number of CRCs in UnpackInfo");
            }
            nid = getUnsignedByte(header);
        }
        if (nid != NID.kEnd) {
            throw new IOException("Badly terminated UnpackInfo");
        }
    }

    /**
     * Skips all the entries if needed. Entries need to be skipped when:
     * <p>
     * 1. it's a random access 2. one of these 2 condition is meet :
     * </p>
     * <p>
     * 2.1 currentEntryIndex != entryIndex : this means there are some entries to be skipped(currentEntryIndex < entryIndex) or the entry has already been
     * read(currentEntryIndex > entryIndex)
     * </p>
     * <p>
     * 2.2 currentEntryIndex == entryIndex && !hasCurrentEntryBeenRead: if the entry to be read is the current entry, but some data of it has been read before,
     * then we need to reopen the stream of the folder and skip all the entries before the current entries
     * </p>
     *
     * @param entryIndex     the entry to be read
     * @param isInSameFolder are the entry to be read and the current entry in the same folder
     * @param folderIndex    the index of the folder which contains the entry
     * @return true if there are entries actually skipped
     * @throws IOException there are exceptions when skipping entries
     * @since 1.21
     */
    private boolean skipEntriesWhenNeeded(final int entryIndex, final boolean isInSameFolder, final int folderIndex) throws IOException {
        final SevenZArchiveEntry file = archive.files[entryIndex];
        // if the entry to be read is the current entry, and the entry has not
        // been read yet, then there's nothing we need to do
        if (currentEntryIndex == entryIndex && !hasCurrentEntryBeenRead()) {
            return false;
        }
        // 1. if currentEntryIndex < entryIndex :
        // this means there are some entries to be skipped(currentEntryIndex < entryIndex)
        // 2. if currentEntryIndex > entryIndex || (currentEntryIndex == entryIndex && hasCurrentEntryBeenRead) :
        // this means the entry has already been read before, and we need to reopen the
        // stream of the folder and skip all the entries before the current entries
        int filesToSkipStartIndex = archive.streamMap.folderFirstFileIndex[currentFolderIndex];
        if (isInSameFolder) {
            if (currentEntryIndex < entryIndex) {
                // the entries between filesToSkipStartIndex and currentEntryIndex had already been skipped
                filesToSkipStartIndex = currentEntryIndex + 1;
            } else {
                // the entry is in the same folder of current entry, but it has already been read before, we need to reset
                // the position of the currentFolderInputStream to the beginning of folder, and then skip the files
                // from the start entry of the folder again
                reopenFolderInputStream(folderIndex, file);
            }
        }
        for (int i = filesToSkipStartIndex; i < entryIndex; i++) {
            final SevenZArchiveEntry fileToSkip = archive.files[i];
            // @formatter:off
            InputStream fileStreamToSkip = BoundedInputStream.builder()
                    .setInputStream(currentFolderInputStream)
                    .setMaxCount(fileToSkip.getSize())
                    .setPropagateClose(false)
                    .get();
            // @formatter:on
            if (fileToSkip.getHasCrc()) {
                // @formatter:off
                fileStreamToSkip = ChecksumInputStream.builder()
                        .setChecksum(new CRC32())
                        .setInputStream(fileStreamToSkip)
                        .setCountThreshold(fileToSkip.getSize())
                        .setExpectedChecksumValue(fileToSkip.getCrcValue())
                        .get();
                // @formatter:on
            }
            deferredBlockStreams.add(fileStreamToSkip);
            // set the content methods as well, it equals to file.getContentMethods() because they are in same folder
            fileToSkip.setContentMethods(file.getContentMethods());
        }
        return true;
    }

    @Override
    public String toString() {
        return archive.toString();
    }

    private Archive tryToLocateEndHeader(final byte[] password) throws IOException {
        final ByteBuffer nidBuf = ByteBuffer.allocate(1);
        final long searchLimit = 1024L * 1024 * 1;
        // Main header, plus bytes that readStartHeader would read
        final long previousDataSize = channel.position() + 20;
        final long minPos;
        // Determine minimal position - can't start before current position
        if (channel.position() + searchLimit > channel.size()) {
            minPos = channel.position();
        } else {
            minPos = channel.size() - searchLimit;
        }
        long pos = channel.size() - 1;
        // Loop: Try from end of archive
        while (pos > minPos) {
            pos--;
            channel.position(pos);
            nidBuf.rewind();
            if (channel.read(nidBuf) < 1) {
                throw new EOFException();
            }
            final int nid = nidBuf.array()[0];
            // First indicator: Byte equals one of these header identifiers
            if (nid == NID.kEncodedHeader || nid == NID.kHeader) {
                try {
                    // Try to initialize Archive structure from here
                    final long nextHeaderOffset = pos - previousDataSize;
                    final long nextHeaderSize = channel.size() - pos;
                    final StartHeader startHeader = new StartHeader(nextHeaderOffset, nextHeaderSize, 0);
                    final Archive result = initializeArchive(startHeader, password, false);
                    // Sanity check: There must be some data...
                    if (result.packSizes.length > 0 && result.files.length > 0) {
                        return result;
                    }
                } catch (final Exception ignored) {
                    // Wrong guess...
                }
            }
        }
        throw new IOException("Start header corrupt and unable to guess end header");
    }
}
