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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.junit.jupiter.api.Test;

class ExplodeSupportTest {

    private void testArchiveWithImplodeCompression(final String fileName, final String entryName) throws IOException {
        try (ZipFile zip = ZipFile.builder().setFile(fileName).get()) {
            final ZipArchiveEntry entry = zip.getEntries().nextElement();
            assertEquals(entryName, entry.getName(), "entry name");
            assertTrue(zip.canReadEntryData(entry), "entry can't be read");
            assertEquals(ZipMethod.IMPLODING.getCode(), entry.getMethod(), "method");

            final ByteArrayOutputStream bout = new ByteArrayOutputStream();
            final CheckedOutputStream out = new CheckedOutputStream(bout, new CRC32());
            try (InputStream inputStream = zip.getInputStream(entry)) {
                IOUtils.copy(inputStream, out);
                out.flush();
            }
            assertEquals(entry.getCrc(), out.getChecksum().getValue(), "CRC32");
        }
    }

    @Test
    void testArchiveWithImplodeCompression4K2Trees() throws IOException {
        testArchiveWithImplodeCompression("target/test-classes/imploding-4Kdict-2trees.zip", "HEADER.TXT");
    }

    @Test
    void testArchiveWithImplodeCompression8K3Trees() throws IOException {
        testArchiveWithImplodeCompression("target/test-classes/imploding-8Kdict-3trees.zip", "LICENSE.TXT");
    }

    @Test
    void testConstructorThrowsExceptions() {
        assertThrows(IllegalArgumentException.class, () -> new ExplodingInputStream(4095, 2, new ByteArrayInputStream(new byte[] {})),
                "should have failed with illegal argument exception");

        assertThrows(IllegalArgumentException.class, () -> new ExplodingInputStream(4096, 4, new ByteArrayInputStream(new byte[] {})),
                "should have failed with illegal argument exception");
    }

    @Test
    void testTikaTestArchive() throws IOException {
        testArchiveWithImplodeCompression("target/test-classes/moby-imploded.zip", "README");
    }

    @Test
    void testTikaTestStream() throws IOException {
        testZipStreamWithImplodeCompression("target/test-classes/moby-imploded.zip", "README");
    }

    private void testZipStreamWithImplodeCompression(final String fileName, final String entryName) throws IOException {
        try (ZipArchiveInputStream zin = new ZipArchiveInputStream(Files.newInputStream(new File(fileName).toPath()))) {
            final ZipArchiveEntry entry = zin.getNextZipEntry();
            assertEquals(entryName, entry.getName(), "entry name");
            assertTrue(zin.canReadEntryData(entry), "entry can't be read");
            assertEquals(ZipMethod.IMPLODING.getCode(), entry.getMethod(), "method");
            try (InputStream bio = new BoundedInputStream(zin, entry.getSize())) {
                final ByteArrayOutputStream bout = new ByteArrayOutputStream();
                final CheckedOutputStream out = new CheckedOutputStream(bout, new CRC32());
                IOUtils.copy(bio, out);
                out.flush();
                assertEquals(entry.getCrc(), out.getChecksum().getValue(), "CRC32");
            }
        }
    }

    @Test
    void testZipStreamWithImplodeCompression4K2Trees() throws IOException {
        testZipStreamWithImplodeCompression("target/test-classes/imploding-4Kdict-2trees.zip", "HEADER.TXT");
    }

    @Test
    void testZipStreamWithImplodeCompression8K3Trees() throws IOException {
        testZipStreamWithImplodeCompression("target/test-classes/imploding-8Kdict-3trees.zip", "LICENSE.TXT");
    }

}
