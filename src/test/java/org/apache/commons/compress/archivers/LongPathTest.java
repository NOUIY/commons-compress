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

package org.apache.commons.compress.archivers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

import org.apache.commons.compress.AbstractTest;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test that can read various tar file examples.
 *
 * Files must be in resources/longpath, and there must be a file.txt containing the list of files in the archives.
 */
class LongPathTest extends AbstractTest {

    private static final ClassLoader CLASS_LOADER = LongPathTest.class.getClassLoader();
    private static final File ARC_DIR;
    private static final ArrayList<String> FILE_LIST = new ArrayList<>();

    static {
        try {
            ARC_DIR = new File(CLASS_LOADER.getResource("longpath").toURI());
        } catch (final URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    public static Stream<Arguments> data() {
        final Collection<Arguments> params = new ArrayList<>();
        for (final String fileName : ARC_DIR.list((dir, name) -> !name.endsWith(".txt"))) {
            params.add(Arguments.of(new File(ARC_DIR, fileName)));
        }
        return params.stream();
    }

    @BeforeAll
    public static void setUpFileList() throws Exception {
        assertTrue(ARC_DIR.exists());
        final File listing = new File(ARC_DIR, "files.txt");
        assertTrue(listing.canRead(), "files.txt is readable");
        try (BufferedReader br = new BufferedReader(Files.newBufferedReader(listing.toPath()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("#")) {
                    FILE_LIST.add(line);
                }
            }
        }
    }

    @Override
    protected String getExpectedString(final ArchiveEntry entry) {
        if (entry instanceof TarArchiveEntry) {
            final TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
            if (tarEntry.isSymbolicLink()) {
                return tarEntry.getName() + " -> " + tarEntry.getLinkName();
            }
        }
        return entry.getName();
    }

    @ParameterizedTest
    @MethodSource("data")
    void testArchive(final File file) throws Exception {
        @SuppressWarnings("unchecked") // fileList is of correct type
        final ArrayList<String> expected = (ArrayList<String>) FILE_LIST.clone();
        final String name = file.getName();
        if ("minotaur.jar".equals(name) || "minotaur-0.jar".equals(name)) {
            expected.add("META-INF/");
            expected.add("META-INF/MANIFEST.MF");
        }
        try (ArchiveInputStream<?> ais = factory.createArchiveInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            // check if expected type recognized
            if (name.endsWith(".tar")) {
                assertInstanceOf(TarArchiveInputStream.class, ais);
            } else if (name.endsWith(".jar") || name.endsWith(".zip")) {
                assertInstanceOf(ZipArchiveInputStream.class, ais);
            } else if (name.endsWith(".cpio")) {
                assertInstanceOf(CpioArchiveInputStream.class, ais);
                // Hack: cpio does not add trailing "/" to directory names
                for (int i = 0; i < expected.size(); i++) {
                    final String ent = expected.get(i);
                    if (ent.endsWith("/")) {
                        expected.set(i, ent.substring(0, ent.length() - 1));
                    }
                }
            } else if (name.endsWith(".ar")) {
                assertInstanceOf(ArArchiveInputStream.class, ais);
                // CPIO does not store directories or directory names
                expected.clear();
                for (final String ent : FILE_LIST) {
                    if (!ent.endsWith("/")) {
                        // not a directory
                        final int lastSlash = ent.lastIndexOf('/');
                        if (lastSlash >= 0) {
                            // extract path name
                            expected.add(ent.substring(lastSlash + 1));
                        } else {
                            expected.add(ent);
                        }
                    }
                }
            } else {
                fail("Unexpected file type: " + name);
            }
            assertDoesNotThrow(() -> checkArchiveContent(ais, expected), "Error processing " + file.getName());
        }
    }
}
