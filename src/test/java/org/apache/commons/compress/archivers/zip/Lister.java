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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.compress.archivers.ArchiveEntry;

/**
 * Simple command line application that lists the contents of a ZIP archive.
 * <p>
 * The name of the archive must be given as a command line argument.
 * </p>
 * <p>
 * Optional command line arguments specify the encoding to assume and whether to use ZipFile or ZipArchiveInputStream.
 * </p>
 */
public final class Lister {

    private static final class CommandLine {

        String archive;
        boolean useStream;
        String encoding;
        boolean allowStoredEntriesWithDataDescriptor;
        Path dir;
    }

    private static void extract(final Path targetDir, final ZipArchiveEntry entry, final InputStream inputStream) throws IOException {
        final Path outputFile = entry.resolveIn(targetDir);
        final Path parent = outputFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.copy(inputStream, outputFile);
    }

    private static void list(final ZipArchiveEntry entry) {
        System.out.println(entry.getName());
    }

    public static void main(final String[] args) throws IOException {
        final CommandLine cl = parse(args);
        final File f = new File(cl.archive);
        if (!f.isFile()) {
            System.err.println(f + " doesn't exists or is a directory");
            usage();
        }
        if (cl.useStream) {
            try (BufferedInputStream fs = new BufferedInputStream(Files.newInputStream(f.toPath()))) {
                final ZipArchiveInputStream zs = new ZipArchiveInputStream(fs, cl.encoding, true, cl.allowStoredEntriesWithDataDescriptor);
                for (ArchiveEntry entry = zs.getNextEntry(); entry != null; entry = zs.getNextEntry()) {
                    final ZipArchiveEntry ze = (ZipArchiveEntry) entry;
                    list(ze);
                    if (cl.dir != null) {
                        extract(cl.dir, ze, zs);
                    }
                }
            }
        } else {
            try (ZipFile zipFile = ZipFile.builder().setFile(f).setCharset(cl.encoding).get()) {
                zipFile.stream().forEach(ze -> {
                    list(ze);
                    if (cl.dir != null) {
                        try (InputStream is = zipFile.getInputStream(ze)) {
                            extract(cl.dir, ze, is);
                        }
                    }
                });
            }
        }
    }

    private static CommandLine parse(final String[] args) {
        final CommandLine cl = new CommandLine();
        boolean error = false;
        final int argsLength = args.length;
        for (int i = 0; i < argsLength; i++) {
            if (args[i].equals("-enc")) {
                if (argsLength > i + 1) {
                    cl.encoding = args[++i];
                } else {
                    System.err.println("missing argument to -enc");
                    error = true;
                }
            } else if (args[i].equals("-extract")) {
                if (argsLength > i + 1) {
                    cl.dir = Paths.get(args[++i]);
                } else {
                    System.err.println("missing argument to -extract");
                    error = true;
                }
            } else if (args[i].equals("-stream")) {
                cl.useStream = true;
            } else if (args[i].equals("+storeddd")) {
                cl.allowStoredEntriesWithDataDescriptor = true;
            } else if (args[i].equals("-file")) {
                cl.useStream = false;
            } else if (cl.archive != null) {
                System.err.println("Only one archive");
                error = true;
            } else {
                cl.archive = args[i];
            }
        }
        if (error || cl.archive == null) {
            usage();
        }
        return cl;
    }

    private static void usage() {
        System.err.println("lister [-enc encoding] [-stream] [-file] [+storeddd] [-extract dir] archive");
        System.exit(1);
    }
}
