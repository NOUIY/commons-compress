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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.AbstractTest;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public final class GZipTest extends AbstractTest {

    private static final int MTIME = 123456000;
    private static final Instant MTIME_INSTANT = Instant.ofEpochSecond(MTIME);

    @Test
    void testConcatenatedStreamsReadFirstOnly() throws Exception {
        final File input = getFile("multiple.gz");
        try (InputStream is = Files.newInputStream(input.toPath())) {
            try (CompressorInputStream in = new CompressorStreamFactory().createCompressorInputStream("gz", is)) {
                assertEquals('a', in.read());
                assertEquals(-1, in.read());
            }
        }
    }

    @Test
    void testConcatenatedStreamsReadFully() throws Exception {
        final File input = getFile("multiple.gz");
        try (InputStream is = Files.newInputStream(input.toPath())) {
            try (CompressorInputStream in = new GzipCompressorInputStream(is, true)) {
                assertEquals('a', in.read());
                assertEquals('b', in.read());
                assertEquals(0, in.available());
                assertEquals(-1, in.read());
            }
        }
    }

    /**
     * @see "https://issues.apache.org/jira/browse/COMPRESS-84"
     */
    @Test
    void testCorruptedInput() throws Exception {
        final byte[] data = readAllBytes("bla.tgz");
        try (InputStream in = new ByteArrayInputStream(data, 0, data.length - 1);
                CompressorInputStream cin = new CompressorStreamFactory().createCompressorInputStream("gz", in);
                OutputStream out = new ByteArrayOutputStream()) {
            assertThrows(IOException.class, () -> IOUtils.copy(cin, out), "Expected an exception");
        }
    }

    private void testExtraFlags(final int compressionLevel, final int flag, final int bufferSize) throws Exception {
        final byte[] content = readAllBytes("test3.xml");

        final ByteArrayOutputStream bout = new ByteArrayOutputStream();

        final GzipParameters parameters = new GzipParameters();
        parameters.setCompressionLevel(compressionLevel);
        parameters.setBufferSize(bufferSize);
        try (GzipCompressorOutputStream out = new GzipCompressorOutputStream(bout, parameters)) {
            IOUtils.copy(new ByteArrayInputStream(content), out);
            out.flush();
        }

        assertEquals(flag, bout.toByteArray()[8], "extra flags (XFL)");
    }

    @Test
    void testExtraFlagsBestCompression() throws Exception {
        testExtraFlags(Deflater.BEST_COMPRESSION, 2, 1024);
    }

    @Test
    void testExtraFlagsDefaultCompression() throws Exception {
        testExtraFlags(Deflater.DEFAULT_COMPRESSION, 0, 4096);
    }

    @Test
    void testExtraFlagsFastestCompression() throws Exception {
        testExtraFlags(Deflater.BEST_SPEED, 4, 128);
    }

    @Test
    void testGzipCreation() throws Exception {
        final File input = getFile("test1.xml");
        final File output = newTempFile("test1.xml.gz");
        try (OutputStream out = Files.newOutputStream(output.toPath())) {
            try (CompressorOutputStream<?> cos = new CompressorStreamFactory().createCompressorOutputStream("gz", out)) {
                cos.write(input);
            }
        }
    }

    @Test
    void testGzipUnarchive() throws Exception {
        final File input = getFile("bla.tgz");
        final File output = newTempFile("bla.tar");
        try (InputStream is = Files.newInputStream(input.toPath())) {
            try (CompressorInputStream in = new CompressorStreamFactory().createCompressorInputStream("gz", is);) {
                Files.copy(in, output.toPath());
            }
        }
    }

    @Test
    void testInteroperabilityWithGzipCompressorInputStream() throws Exception {
        final byte[] content = readAllBytes("test3.xml");
        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        final GzipParameters parameters = new GzipParameters();
        parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
        parameters.setOperatingSystem(3);
        parameters.setFilename("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setFileName("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setComment("Test file");
        parameters.setModificationTime(System.currentTimeMillis());
        try (GzipCompressorOutputStream out = new GzipCompressorOutputStream(bout, parameters)) {
            out.write(content);
            out.flush();
        }
        try (GzipCompressorInputStream in = new GzipCompressorInputStream(new ByteArrayInputStream(bout.toByteArray()))) {
            final byte[] content2 = IOUtils.toByteArray(in);
            assertArrayEquals(content, content2, "uncompressed content");
        }
    }

    @Test
    void testInteroperabilityWithGZIPInputStream() throws Exception {
        final byte[] content = readAllBytes("test3.xml");
        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        final GzipParameters parameters = new GzipParameters();
        parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
        parameters.setOperatingSystem(3);
        parameters.setFilename("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setFileName("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setComment("Test file");
        parameters.setModificationTime(System.currentTimeMillis());
        try (GzipCompressorOutputStream out = new GzipCompressorOutputStream(bout, parameters)) {
            out.write(content);
            out.flush();
        }
        final GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bout.toByteArray()));
        final byte[] content2 = IOUtils.toByteArray(in);
        assertArrayEquals(content, content2, "uncompressed content");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void testInvalidBufferSize(final int bufferSize) {
        final GzipParameters parameters = new GzipParameters();
        assertThrows(IllegalArgumentException.class, () -> parameters.setBufferSize(bufferSize), "IllegalArgumentException not thrown");
    }

    @ParameterizedTest
    @ValueSource(ints = { 10, -5 })
    void testInvalidCompressionLevel(final int compressionLevel) {
        final GzipParameters parameters = new GzipParameters();
        assertThrows(IllegalArgumentException.class, () -> parameters.setCompressionLevel(compressionLevel), "IllegalArgumentException not thrown");
    }

    @Test
    void testMetadataRoundTrip() throws Exception {
        final ByteArrayOutputStream bout = new ByteArrayOutputStream();

        final GzipParameters parameters = new GzipParameters();
        parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
        assertEquals(0, parameters.getModificationTime());
        parameters.setModificationInstant(null);
        assertEquals(0, parameters.getModificationTime());
        parameters.setModificationInstant(MTIME_INSTANT);
        assertEquals(MTIME_INSTANT.getEpochSecond(), parameters.getModificationTime());
        assertEquals(MTIME_INSTANT, parameters.getModificationInstant());
        parameters.setOS(GzipParameters.OS.Z_SYSTEM);
        assertEquals(GzipParameters.OS.Z_SYSTEM, parameters.getOS());
        parameters.setOS(null);
        assertEquals(GzipParameters.OS.UNKNOWN, parameters.getOS());
        parameters.setOperatingSystem(13);
        assertEquals(GzipParameters.OS.ACORN_RISCOS, parameters.getOS());
        parameters.setFilename("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setFileName("test3.xml");
        assertEquals(parameters.getFilename(), parameters.getFileName());
        parameters.setComment("Umlaute möglich?");
        try (GzipCompressorOutputStream out = new GzipCompressorOutputStream(bout, parameters)) {
            out.write(getFile("test3.xml"));
        }

        final GzipCompressorInputStream input = new GzipCompressorInputStream(new ByteArrayInputStream(bout.toByteArray()));
        input.close();
        final GzipParameters readParams = input.getMetaData();
        assertEquals(Deflater.BEST_COMPRESSION, readParams.getCompressionLevel());
        assertEquals(123456000, readParams.getModificationTime());
        assertEquals(13, readParams.getOperatingSystem());
        assertEquals(GzipParameters.OS.ACORN_RISCOS, readParams.getOS());
        assertEquals("test3.xml", readParams.getFileName());
        assertEquals("test3.xml", readParams.getFilename());
        assertEquals("Umlaute möglich?", readParams.getComment());
    }

    @Test
    void testMultiByteReadConsistentlyReturnsMinusOneAtEof() throws IOException {
        final File input = getFile("bla.tgz");
        final byte[] buf = new byte[2];
        try (InputStream is = Files.newInputStream(input.toPath());
                GzipCompressorInputStream in = new GzipCompressorInputStream(is)) {
            IOUtils.toByteArray(in);
            assertEquals(-1, in.read(buf));
            assertEquals(-1, in.read(buf));
        }
    }

    @Test
    void testOverWrite() throws Exception {
        final GzipCompressorOutputStream out = new GzipCompressorOutputStream(new ByteArrayOutputStream());
        out.close();
        assertThrows(IOException.class, () -> out.write(0));
    }

    @Test
    void testSingleByteReadConsistentlyReturnsMinusOneAtEof() throws IOException {
        final File input = getFile("bla.tgz");
        try (InputStream is = Files.newInputStream(input.toPath());
                GzipCompressorInputStream in = new GzipCompressorInputStream(is)) {
            IOUtils.toByteArray(in);
            assertEquals(-1, in.read());
            assertEquals(-1, in.read());
        }
    }
}
