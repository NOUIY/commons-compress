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
package org.apache.commons.compress.harmony.unpack200.bytecode;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The abstract superclass for all types of class file entries.
 */
public abstract class ClassFileEntry {

    /**
     * An empty ClassFileEntry array.
     */
    protected static final ClassFileEntry[] NONE = {};
    private boolean resolved;

    /**
     * Writes this instance to the output stream.
     *
     * @param dos the output stream.
     * @throws IOException if an I/O error occurs.
     */
    protected abstract void doWrite(DataOutputStream dos) throws IOException;

    @Override
    public abstract boolean equals(Object arg0);

    /**
     * Returns an empty array.
     *
     * @return an empty array.
     */
    protected ClassFileEntry[] getNestedClassFileEntries() {
        return NONE;
    }

    @Override
    public abstract int hashCode();

    /**
     * Delegates to super {@link #hashCode}.
     *
     * @return super {@link #hashCode}.
     */
    protected int objectHashCode() {
        return super.hashCode();
    }

    /**
     * Allows the constant pool entries to resolve their nested entries.
     *
     * @param pool The class constant pool.
     */
    protected void resolve(final ClassConstantPool pool) {
        resolved = true;
    }

    @Override
    public abstract String toString();

    /**
     * Writes this instance to the output stream.
     *
     * @param dos the output stream.
     * @throws IOException if an I/O error occurs.
     */
    public final void write(final DataOutputStream dos) throws IOException {
        if (!resolved) {
            throw new IllegalStateException("Entry has not been resolved");
        }
        doWrite(dos);
    }

}
