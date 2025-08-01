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
package org.apache.commons.compress.compressors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream;
import org.apache.commons.compress.compressors.brotli.BrotliUtils;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorInputStream;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream;
import org.apache.commons.compress.compressors.deflate64.Deflate64CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMAUtils;
import org.apache.commons.compress.compressors.pack200.Pack200CompressorInputStream;
import org.apache.commons.compress.compressors.pack200.Pack200CompressorOutputStream;
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorInputStream;
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorOutputStream;
import org.apache.commons.compress.compressors.snappy.SnappyCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZUtils;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdUtils;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.Sets;
import org.apache.commons.lang3.StringUtils;

/**
 * <p>
 * Creates a Compressor[In|Out]putStreams from names. To add other implementations you should extend CompressorStreamFactory and override the
 * appropriate methods (and call their implementation from super of course).
 * </p>
 *
 * Example (Compressing a file):
 *
 * <pre>
 * final OutputStream out = Files.newOutputStream(output.toPath());
 * CompressorOutputStream cos = new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.BZIP2, out);
 * IOUtils.copy(Files.newInputStream(input.toPath()), cos);
 * cos.close();
 * </pre>
 *
 * Example (Decompressing a file):
 *
 * <pre>
 * final InputStream is = Files.newInputStream(input.toPath());
 * CompressorInputStream in = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, is);
 * IOUtils.copy(in, Files.newOutputStream(output.toPath()));
 * in.close();
 * </pre>
 *
 * @Immutable provided that the deprecated method setDecompressConcatenated is not used.
 * @ThreadSafe even if the deprecated method setDecompressConcatenated is used
 */
public class CompressorStreamFactory implements CompressorStreamProvider {

    private static final CompressorStreamFactory SINGLETON = new CompressorStreamFactory();

    /**
     * Constant (value {@value}) used to identify the BROTLI compression algorithm.
     *
     * @since 1.14
     */
    public static final String BROTLI = "br";

    /**
     * Constant (value {@value}) used to identify the BZIP2 compression algorithm.
     *
     * @since 1.1
     */
    public static final String BZIP2 = "bzip2";

    /**
     * Constant (value {@value}) used to identify the GZIP compression algorithm.
     *
     * @since 1.1
     */
    public static final String GZIP = "gz";

    /**
     * Constant (value {@value}) used to identify the PACK200 compression algorithm.
     *
     * @since 1.3
     */
    public static final String PACK200 = "pack200";

    /**
     * Constant (value {@value}) used to identify the XZ compression method.
     *
     * @since 1.4
     */
    public static final String XZ = "xz";

    /**
     * Constant (value {@value}) used to identify the LZMA compression method.
     *
     * @since 1.6
     */
    public static final String LZMA = "lzma";

    /**
     * Constant (value {@value}) used to identify the "framed" Snappy compression method.
     *
     * @since 1.7
     */
    public static final String SNAPPY_FRAMED = "snappy-framed";

    /**
     * Constant (value {@value}) used to identify the "raw" Snappy compression method. Not supported as an output stream type.
     *
     * @since 1.7
     */
    public static final String SNAPPY_RAW = "snappy-raw";

    /**
     * Constant (value {@value}) used to identify the traditional Unix compress method. Not supported as an output stream type.
     *
     * @since 1.7
     */
    public static final String Z = "z";

    /**
     * Constant (value {@value}) used to identify the Deflate compress method.
     *
     * @since 1.9
     */
    public static final String DEFLATE = "deflate";

    /**
     * Constant (value {@value}) used to identify the Deflate64 compress method.
     *
     * @since 1.16
     */
    public static final String DEFLATE64 = "deflate64";

    /**
     * Constant (value {@value}) used to identify the block LZ4 compression method.
     *
     * @since 1.14
     */
    public static final String LZ4_BLOCK = "lz4-block";

    /**
     * Constant (value {@value}) used to identify the frame LZ4 compression method.
     *
     * @since 1.14
     */
    public static final String LZ4_FRAMED = "lz4-framed";

    /**
     * Constant (value {@value}) used to identify the Zstandard compression algorithm. Not supported as an output stream type.
     *
     * @since 1.16
     */
    public static final String ZSTANDARD = "zstd";

    private static final String YOU_NEED_BROTLI_DEC = youNeed("Google Brotli Dec", "https://github.com/google/brotli/");
    private static final String YOU_NEED_XZ_JAVA = youNeed("XZ for Java", "https://tukaani.org/xz/java.html");
    private static final String YOU_NEED_ZSTD_JNI = youNeed("Zstd JNI", "https://github.com/luben/zstd-jni");

    private static final Set<String> ALL_NAMES = Sets.newHashSet(BZIP2, GZIP, PACK200, SNAPPY_FRAMED, Z, DEFLATE, XZ, LZMA, LZ4_FRAMED, ZSTANDARD);

    private static Iterable<CompressorStreamProvider> archiveStreamProviderIterable() {
        return ServiceLoader.load(CompressorStreamProvider.class, ClassLoader.getSystemClassLoader());
    }

    /**
     * Detects the type of compressor stream.
     *
     * @param inputStream input stream
     * @return type of compressor stream detected
     * @throws CompressorException      if no compressor stream type was detected or if something else went wrong
     * @throws IllegalArgumentException if stream is null or does not support mark
     * @since 1.14
     */
    public static String detect(final InputStream inputStream) throws CompressorException {
        return detect(inputStream, ALL_NAMES);
    }

    /**
     * Detects the type of compressor stream while limiting the type to the provided set of compressor names.
     *
     * @param inputStream     input stream
     * @param compressorNames compressor names to limit autodetection
     * @return type of compressor stream detected
     * @throws CompressorException      if no compressor stream type was detected or if something else went wrong
     * @throws IllegalArgumentException if stream is null or does not support mark
     */
    static String detect(final InputStream inputStream, final Set<String> compressorNames) throws CompressorException {
        if (inputStream == null) {
            throw new IllegalArgumentException("Stream must not be null.");
        }
        if (compressorNames == null || compressorNames.isEmpty()) {
            throw new IllegalArgumentException("Compressor names cannot be null or empty");
        }
        if (!inputStream.markSupported()) {
            throw new IllegalArgumentException("Mark is not supported.");
        }
        final byte[] signature = new byte[12];
        inputStream.mark(signature.length);
        int signatureLength = -1;
        try {
            signatureLength = IOUtils.readFully(inputStream, signature);
            inputStream.reset();
        } catch (final IOException e) {
            throw new CompressorException("Failed to read signature.", e);
        }
        if (compressorNames.contains(BZIP2) && BZip2CompressorInputStream.matches(signature, signatureLength)) {
            return BZIP2;
        }
        if (compressorNames.contains(GZIP) && GzipCompressorInputStream.matches(signature, signatureLength)) {
            return GZIP;
        }
        if (compressorNames.contains(PACK200) && Pack200CompressorInputStream.matches(signature, signatureLength)) {
            return PACK200;
        }
        if (compressorNames.contains(SNAPPY_FRAMED) && FramedSnappyCompressorInputStream.matches(signature, signatureLength)) {
            return SNAPPY_FRAMED;
        }
        if (compressorNames.contains(Z) && ZCompressorInputStream.matches(signature, signatureLength)) {
            return Z;
        }
        if (compressorNames.contains(DEFLATE) && DeflateCompressorInputStream.matches(signature, signatureLength)) {
            return DEFLATE;
        }
        if (compressorNames.contains(XZ) && XZUtils.matches(signature, signatureLength)) {
            return XZ;
        }
        if (compressorNames.contains(LZMA) && LZMAUtils.matches(signature, signatureLength)) {
            return LZMA;
        }
        if (compressorNames.contains(LZ4_FRAMED) && FramedLZ4CompressorInputStream.matches(signature, signatureLength)) {
            return LZ4_FRAMED;
        }
        if (compressorNames.contains(ZSTANDARD) && ZstdUtils.matches(signature, signatureLength)) {
            return ZSTANDARD;
        }
        throw new CompressorException("No Compressor found for the stream signature.");
    }

    /**
     * Constructs a new sorted map from input stream provider names to provider objects.
     *
     * <p>
     * The map returned by this method will have one entry for each provider for which support is available in the current Java virtual machine. If two or more
     * supported provider have the same name then the resulting map will contain just one of them; which one it will contain is not specified.
     * </p>
     *
     * <p>
     * The invocation of this method, and the subsequent use of the resulting map, may cause time-consuming disk or network I/O operations to occur. This method
     * is provided for applications that need to enumerate all of the available providers, for example to allow user provider selection.
     * </p>
     *
     * <p>
     * This method may return different results at different times if new providers are dynamically made available to the current Java virtual machine.
     * </p>
     *
     * @return An immutable, map from names to provider objects
     * @since 1.13
     */
    public static SortedMap<String, CompressorStreamProvider> findAvailableCompressorInputStreamProviders() {
        return AccessController.doPrivileged((PrivilegedAction<SortedMap<String, CompressorStreamProvider>>) () -> {
            final TreeMap<String, CompressorStreamProvider> map = new TreeMap<>();
            putAll(SINGLETON.getInputStreamCompressorNames(), SINGLETON, map);
            archiveStreamProviderIterable().forEach(provider -> putAll(provider.getInputStreamCompressorNames(), provider, map));
            return map;
        });
    }

    /**
     * Constructs a new sorted map from output stream provider names to provider objects.
     *
     * <p>
     * The map returned by this method will have one entry for each provider for which support is available in the current Java virtual machine. If two or more
     * supported provider have the same name then the resulting map will contain just one of them; which one it will contain is not specified.
     * </p>
     *
     * <p>
     * The invocation of this method, and the subsequent use of the resulting map, may cause time-consuming disk or network I/O operations to occur. This method
     * is provided for applications that need to enumerate all of the available providers, for example to allow user provider selection.
     * </p>
     *
     * <p>
     * This method may return different results at different times if new providers are dynamically made available to the current Java virtual machine.
     * </p>
     *
     * @return An immutable, map from names to provider objects
     * @since 1.13
     */
    public static SortedMap<String, CompressorStreamProvider> findAvailableCompressorOutputStreamProviders() {
        return AccessController.doPrivileged((PrivilegedAction<SortedMap<String, CompressorStreamProvider>>) () -> {
            final TreeMap<String, CompressorStreamProvider> map = new TreeMap<>();
            putAll(SINGLETON.getOutputStreamCompressorNames(), SINGLETON, map);
            archiveStreamProviderIterable().forEach(provider -> putAll(provider.getOutputStreamCompressorNames(), provider, map));
            return map;
        });
    }

    /**
     * Gets the string used to identify the {@link #BROTLI} compression algorithm.
     *
     * @return the string used to identify the {@link #BROTLI} compression algorithm.
     */
    public static String getBrotli() {
        return BROTLI;
    }

    /**
     * Gets the string used to identify the {@link #BZIP2} compression algorithm.
     *
     * @return the string used to identify the {@link #BZIP2} compression algorithm.
     */
    public static String getBzip2() {
        return BZIP2;
    }

    /**
     * Gets the string used to identify the {@link #DEFLATE} compression algorithm.
     *
     * @return the string used to identify the {@link #DEFLATE} compression algorithm.
     */
    public static String getDeflate() {
        return DEFLATE;
    }

    /**
     * Gets the string used to identify the {@link #DEFLATE64} compression algorithm.
     *
     * @return the string used to identify the {@link #DEFLATE64} compression algorithm.
     * @since 1.16
     */
    public static String getDeflate64() {
        return DEFLATE64;
    }

    /**
     * Gets the string used to identify the {@link #GZIP} compression algorithm.
     *
     * @return the string used to identify the {@link #GZIP} compression algorithm.
     */
    public static String getGzip() {
        return GZIP;
    }

    /**
     * Gets the string used to identify the {@link #LZ4_BLOCK} compression algorithm.
     *
     * @return the string used to identify the {@link #LZ4_BLOCK} compression algorithm.
     */
    public static String getLZ4Block() {
        return LZ4_BLOCK;
    }

    /**
     * Gets the string used to identify the {@link #GZIP} compression algorithm.
     *
     * @return the string used to identify the {@link #GZIP} compression algorithm.
     */
    public static String getLZ4Framed() {
        return LZ4_FRAMED;
    }

    /**
     * Gets the string used to identify the {@link #LZMA} compression algorithm.
     *
     * @return the string used to identify the {@link #LZMA} compression algorithm.
     */
    public static String getLzma() {
        return LZMA;
    }

    /**
     * Gets the string used to identify the {@link #PACK200} compression algorithm.
     *
     * @return the string used to identify the {@link #PACK200} compression algorithm.
     */
    public static String getPack200() {
        return PACK200;
    }

    /**
     * Gets singleton instance.
     *
     * @return the singleton instance.
     */
    public static CompressorStreamFactory getSingleton() {
        return SINGLETON;
    }

    /**
     * Gets the string used to identify the {@link #SNAPPY_FRAMED} compression algorithm.
     *
     * @return the string used to identify the {@link #SNAPPY_FRAMED} compression algorithm.
     */
    public static String getSnappyFramed() {
        return SNAPPY_FRAMED;
    }

    /**
     * Gets the string used to identify the {@link #SNAPPY_RAW} compression algorithm.
     *
     * @return the string used to identify the {@link #SNAPPY_RAW} compression algorithm.
     */
    public static String getSnappyRaw() {
        return SNAPPY_RAW;
    }

    /**
     * Gets the string used to identify the {@link #XZ} compression algorithm.
     *
     * @return the string used to identify the {@link #XZ} compression algorithm.
     */
    public static String getXz() {
        return XZ;
    }

    /**
     * Gets the string used to identify the {@link #Z} compression algorithm.
     *
     * @return the string used to identify the {@link #Z} compression algorithm.
     */
    public static String getZ() {
        return Z;
    }

    /**
     * Gets the string used to identify the {@link #ZSTANDARD} compression algorithm.
     *
     * @return the string used to identify the {@link #ZSTANDARD} compression algorithm.
     */
    public static String getZstandard() {
        return ZSTANDARD;
    }

    static void putAll(final Set<String> names, final CompressorStreamProvider provider, final TreeMap<String, CompressorStreamProvider> map) {
        names.forEach(name -> map.put(toKey(name), provider));
    }

    private static String toKey(final String name) {
        return StringUtils.toRootUpperCase(name);
    }

    private static String youNeed(final String name, final String url) {
        return " In addition to Apache Commons Compress you need the " + name + " library - see " + url;
    }

    /**
     * If true, decompress until the end of the input. If false, stop after the first stream and leave the input position to point to the next byte after the
     * stream
     */
    private final Boolean decompressUntilEof;
    // This is Boolean so setDecompressConcatenated can determine whether it has
    // been set by the ctor
    // once the setDecompressConcatenated method has been removed, it can revert
    // to boolean

    private SortedMap<String, CompressorStreamProvider> compressorInputStreamProviders;

    private SortedMap<String, CompressorStreamProvider> compressorOutputStreamProviders;

    /**
     * If true, decompress until the end of the input. If false, stop after the first stream and leave the input position to point to the next byte after the
     * stream
     */
    private volatile boolean decompressConcatenated;

    private final int memoryLimitInKb;

    /**
     * Constructs an instance with the decompress Concatenated option set to false.
     */
    public CompressorStreamFactory() {
        this.decompressUntilEof = null;
        this.memoryLimitInKb = -1;
    }

    /**
     * Constructs an instance with the provided decompress Concatenated option.
     *
     * @param decompressUntilEOF if true, decompress until the end of the input; if false, stop after the first stream and leave the input position to point to
     *                           the next byte after the stream. This setting applies to the gzip, bzip2 and XZ formats only.
     * @since 1.10
     */
    public CompressorStreamFactory(final boolean decompressUntilEOF) {
        this(decompressUntilEOF, -1);
    }

    /**
     * Constructs an instance with the provided decompress Concatenated option.
     *
     * @param decompressUntilEOF if true, decompress until the end of the input; if false, stop after the first stream and leave the input position to point to
     *                           the next byte after the stream. This setting applies to the gzip, bzip2 and XZ formats only.
     * @param memoryLimitInKb    Some streams require allocation of potentially significant byte arrays/tables, and they can offer checks to prevent OOMs on
     *                           corrupt files. Set the maximum allowed memory allocation in KBs.
     *
     * @since 1.14
     */
    public CompressorStreamFactory(final boolean decompressUntilEOF, final int memoryLimitInKb) {
        this.decompressUntilEof = decompressUntilEOF;
        // Also copy to existing variable so can continue to use that as the
        // current value
        this.decompressConcatenated = decompressUntilEOF;
        this.memoryLimitInKb = memoryLimitInKb;
    }

    /**
     * Creates a compressor input stream from an input stream, auto-detecting the compressor type from the first few bytes of the stream. The InputStream must
     * support marks, like BufferedInputStream.
     *
     * @param in the input stream
     * @return the compressor input stream
     * @throws CompressorException      if the compressor name is not known
     * @throws IllegalArgumentException if the stream is null or does not support mark
     * @since 1.1
     */
    public CompressorInputStream createCompressorInputStream(final InputStream in) throws CompressorException {
        return createCompressorInputStream(detect(in), in);
    }

    /**
     * Creates a compressor input stream from an input stream, auto-detecting the compressor type from the first few bytes of the stream while limiting the
     * detected type to the provided set of compressor names. The InputStream must support marks, like BufferedInputStream.
     *
     * @param in              the input stream
     * @param compressorNames compressor names to limit autodetection
     * @return the compressor input stream
     * @throws CompressorException      if the autodetected compressor is not in the provided set of compressor names
     * @throws IllegalArgumentException if the stream is null or does not support mark
     * @since 1.25.0
     */
    public CompressorInputStream createCompressorInputStream(final InputStream in, final Set<String> compressorNames) throws CompressorException {
        return createCompressorInputStream(detect(in, compressorNames), in);
    }

    /**
     * Creates a compressor input stream from a compressor name and an input stream.
     *
     * @param name of the compressor, i.e. {@value #GZIP}, {@value #BZIP2}, {@value #XZ}, {@value #LZMA}, {@value #PACK200}, {@value #SNAPPY_RAW},
     *             {@value #SNAPPY_FRAMED}, {@value #Z}, {@value #LZ4_BLOCK}, {@value #LZ4_FRAMED}, {@value #ZSTANDARD}, {@value #DEFLATE64} or
     *             {@value #DEFLATE}
     * @param in   the input stream
     * @return compressor input stream
     * @throws CompressorException      if the compressor name is not known or not available, or if there's an IOException or MemoryLimitException thrown during
     *                                  initialization
     * @throws IllegalArgumentException if the name or input stream is null
     */
    public CompressorInputStream createCompressorInputStream(final String name, final InputStream in) throws CompressorException {
        return createCompressorInputStream(name, in, decompressConcatenated);
    }

    @Override
    public CompressorInputStream createCompressorInputStream(final String name, final InputStream in, final boolean actualDecompressConcatenated)
            throws CompressorException {
        if (name == null || in == null) {
            throw new IllegalArgumentException("Compressor name and stream must not be null.");
        }
        try {
            if (GZIP.equalsIgnoreCase(name)) {
                // @formatter:off
                return GzipCompressorInputStream.builder()
                        .setInputStream(in)
                        .setDecompressConcatenated(actualDecompressConcatenated)
                        .get();
                // @formatter:on
            }
            if (BZIP2.equalsIgnoreCase(name)) {
                return new BZip2CompressorInputStream(in, actualDecompressConcatenated);
            }
            if (BROTLI.equalsIgnoreCase(name)) {
                if (!BrotliUtils.isBrotliCompressionAvailable()) {
                    throw new CompressorException("Brotli compression is not available." + YOU_NEED_BROTLI_DEC);
                }
                return new BrotliCompressorInputStream(in);
            }
            if (XZ.equalsIgnoreCase(name)) {
                if (!XZUtils.isXZCompressionAvailable()) {
                    throw new CompressorException("XZ compression is not available." + YOU_NEED_XZ_JAVA);
                }
                // @formatter:off
                return XZCompressorInputStream.builder()
                        .setInputStream(in)
                        .setDecompressConcatenated(actualDecompressConcatenated)
                        .setMemoryLimitKiB(memoryLimitInKb)
                        .get();
                // @formatter:on
            }
            if (ZSTANDARD.equalsIgnoreCase(name)) {
                if (!ZstdUtils.isZstdCompressionAvailable()) {
                    throw new CompressorException("Zstandard compression is not available." + YOU_NEED_ZSTD_JNI);
                }
                return new ZstdCompressorInputStream(in);
            }
            if (LZMA.equalsIgnoreCase(name)) {
                if (!LZMAUtils.isLZMACompressionAvailable()) {
                    throw new CompressorException("LZMA compression is not available" + YOU_NEED_XZ_JAVA);
                }
                return LZMACompressorInputStream.builder().setInputStream(in).setMemoryLimitKiB(memoryLimitInKb).get();
            }
            if (PACK200.equalsIgnoreCase(name)) {
                return new Pack200CompressorInputStream(in);
            }
            if (SNAPPY_RAW.equalsIgnoreCase(name)) {
                return new SnappyCompressorInputStream(in);
            }
            if (SNAPPY_FRAMED.equalsIgnoreCase(name)) {
                return new FramedSnappyCompressorInputStream(in);
            }
            if (Z.equalsIgnoreCase(name)) {
                return new ZCompressorInputStream(in, memoryLimitInKb);
            }
            if (DEFLATE.equalsIgnoreCase(name)) {
                return new DeflateCompressorInputStream(in);
            }
            if (DEFLATE64.equalsIgnoreCase(name)) {
                return new Deflate64CompressorInputStream(in);
            }
            if (LZ4_BLOCK.equalsIgnoreCase(name)) {
                return new BlockLZ4CompressorInputStream(in);
            }
            if (LZ4_FRAMED.equalsIgnoreCase(name)) {
                return new FramedLZ4CompressorInputStream(in, actualDecompressConcatenated);
            }

        } catch (final IOException e) {
            throw new CompressorException("Could not create CompressorInputStream.", e);
        }
        final CompressorStreamProvider compressorStreamProvider = getCompressorInputStreamProviders().get(toKey(name));
        if (compressorStreamProvider != null) {
            return compressorStreamProvider.createCompressorInputStream(name, in, actualDecompressConcatenated);
        }
        throw new CompressorException("Compressor: " + name + " not found.");
    }

    /**
     * Creates a compressor output stream from a compressor name and an output stream.
     *
     * @param name the compressor name, i.e. {@value #GZIP}, {@value #BZIP2}, {@value #XZ}, {@value #PACK200}, {@value #SNAPPY_FRAMED}, {@value #LZ4_BLOCK},
     *             {@value #LZ4_FRAMED}, {@value #ZSTANDARD} or {@value #DEFLATE}
     * @param out  the output stream
     * @return the compressor output stream
     * @throws CompressorException      if the archiver name is not known
     * @throws IllegalArgumentException if the archiver name or stream is null
     */
    @SuppressWarnings("unchecked")
    @Override
    public CompressorOutputStream<? extends OutputStream> createCompressorOutputStream(final String name, final OutputStream out) throws CompressorException {
        if (name == null || out == null) {
            throw new IllegalArgumentException("Compressor name and stream must not be null.");
        }
        try {
            if (GZIP.equalsIgnoreCase(name)) {
                return new GzipCompressorOutputStream(out);
            }
            if (BZIP2.equalsIgnoreCase(name)) {
                return new BZip2CompressorOutputStream(out);
            }
            if (XZ.equalsIgnoreCase(name)) {
                return new XZCompressorOutputStream(out);
            }
            if (PACK200.equalsIgnoreCase(name)) {
                return new Pack200CompressorOutputStream(out);
            }
            if (LZMA.equalsIgnoreCase(name)) {
                return new LZMACompressorOutputStream(out);
            }
            if (DEFLATE.equalsIgnoreCase(name)) {
                return new DeflateCompressorOutputStream(out);
            }
            if (SNAPPY_FRAMED.equalsIgnoreCase(name)) {
                return new FramedSnappyCompressorOutputStream(out);
            }
            if (LZ4_BLOCK.equalsIgnoreCase(name)) {
                return new BlockLZ4CompressorOutputStream(out);
            }
            if (LZ4_FRAMED.equalsIgnoreCase(name)) {
                return new FramedLZ4CompressorOutputStream(out);
            }
            if (ZSTANDARD.equalsIgnoreCase(name)) {
                return new ZstdCompressorOutputStream(out);
            }
        } catch (final IOException e) {
            throw new CompressorException("Could not create CompressorOutputStream.", e);
        }
        final CompressorStreamProvider compressorStreamProvider = getCompressorOutputStreamProviders().get(toKey(name));
        if (compressorStreamProvider != null) {
            return compressorStreamProvider.createCompressorOutputStream(name, out);
        }
        throw new CompressorException("Compressor: " + name + " not found.");
    }

    /**
     * Gets a sorted map of compression input stream providers.
     *
     * @return a sorted map of compression input stream providers.
     */
    public SortedMap<String, CompressorStreamProvider> getCompressorInputStreamProviders() {
        if (compressorInputStreamProviders == null) {
            compressorInputStreamProviders = Collections.unmodifiableSortedMap(findAvailableCompressorInputStreamProviders());
        }
        return compressorInputStreamProviders;
    }

    /**
     * Gets a sorted map of compression output stream providers.
     *
     * @return a sorted map of compression output stream providers.
     */
    public SortedMap<String, CompressorStreamProvider> getCompressorOutputStreamProviders() {
        if (compressorOutputStreamProviders == null) {
            compressorOutputStreamProviders = Collections.unmodifiableSortedMap(findAvailableCompressorOutputStreamProviders());
        }
        return compressorOutputStreamProviders;
    }

    /** For tests. */
    boolean getDecompressConcatenated() {
        return decompressConcatenated;
    }

    /**
     * Tests whether we decompress until the end of the input. If false, stop after the first stream and leave the input position to point to the next byte
     * after the stream.
     *
     * @return whether we decompress until the end of the input.
     */
    public Boolean getDecompressUntilEOF() {
        return decompressUntilEof;
    }

    @Override
    public Set<String> getInputStreamCompressorNames() {
        return Sets.newHashSet(GZIP, BROTLI, BZIP2, XZ, LZMA, PACK200, DEFLATE, SNAPPY_RAW, SNAPPY_FRAMED, Z, LZ4_BLOCK, LZ4_FRAMED, ZSTANDARD, DEFLATE64);
    }

    @Override
    public Set<String> getOutputStreamCompressorNames() {
        return Sets.newHashSet(GZIP, BZIP2, XZ, LZMA, PACK200, DEFLATE, SNAPPY_FRAMED, LZ4_BLOCK, LZ4_FRAMED, ZSTANDARD);
    }

    /**
     * Sets whether to decompress the full input or only the first stream in formats supporting multiple concatenated input streams.
     *
     * <p>
     * This setting applies to the gzip, bzip2 and XZ formats only.
     * </p>
     *
     * @param decompressConcatenated if true, decompress until the end of the input; if false, stop after the first stream and leave the input position to point
     *                               to the next byte after the stream
     * @since 1.5
     * @deprecated 1.10 use the {@link #CompressorStreamFactory(boolean)} constructor instead
     * @throws IllegalStateException if the constructor {@link #CompressorStreamFactory(boolean)} was used to create the factory
     */
    @Deprecated
    public void setDecompressConcatenated(final boolean decompressConcatenated) {
        if (this.decompressUntilEof != null) {
            throw new IllegalStateException("Cannot override the setting defined by the constructor");
        }
        this.decompressConcatenated = decompressConcatenated;
    }

}
