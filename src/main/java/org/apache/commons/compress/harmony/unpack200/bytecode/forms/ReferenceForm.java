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
package org.apache.commons.compress.harmony.unpack200.bytecode.forms;

import java.util.Objects;

import org.apache.commons.compress.harmony.pack200.Pack200Exception;
import org.apache.commons.compress.harmony.unpack200.SegmentConstantPool;
import org.apache.commons.compress.harmony.unpack200.bytecode.ByteCode;
import org.apache.commons.compress.harmony.unpack200.bytecode.ClassFileEntry;
import org.apache.commons.compress.harmony.unpack200.bytecode.OperandManager;

/**
 * Abstract class of all ByteCodeForms which add a nested entry from the globalConstantPool.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/13/docs/specs/pack-spec.html">Pack200: A Packed Class Deployment Format For Java Applications</a>
 */
public abstract class ReferenceForm extends ByteCodeForm {

    /**
     * Constructs a new instance with the specified opcode, name, operandType and rewrite.
     *
     * @param opcode  index corresponding to the opcode's value.
     * @param name    String printable name of the opcode.
     * @param rewrite Operand positions (which will later be rewritten in ByteCodes) are indicated by -1.
     */
    public ReferenceForm(final int opcode, final String name, final int[] rewrite) {
        super(opcode, name, rewrite);
    }

    protected abstract int getOffset(OperandManager operandManager);

    protected abstract int getPoolID();

    /*
     * (non-Javadoc)
     *
     * @see org.apache.commons.compress.harmony.unpack200.bytecode.forms.ByteCodeForm#setByteCodeOperands(org.apache.commons.
     * compress.harmony.unpack200.bytecode.ByteCode, org.apache.commons.compress.harmony.unpack200.bytecode.OperandTable,
     * org.apache.commons.compress.harmony.unpack200.Segment)
     */
    @Override
    public void setByteCodeOperands(final ByteCode byteCode, final OperandManager operandManager, final int codeLength) throws Pack200Exception {
        final int offset = getOffset(operandManager);
        try {
            setNestedEntries(byteCode, operandManager, offset);
        } catch (final Pack200Exception ex) {
            throw new Error("Got a pack200 exception. What to do?");
        }
    }

    /**
     * Sets the nested entries.
     *
     * @param byteCode byte codes.
     * @param operandManager Operand manager.
     * @param offset offset.
     * @throws Pack200Exception if support for a type is not supported or the offset not in the range [0, {@link Integer#MAX_VALUE}].
     */
    protected void setNestedEntries(final ByteCode byteCode, final OperandManager operandManager, final int offset) throws Pack200Exception {
        final SegmentConstantPool globalPool = operandManager.globalConstantPool();
        final ClassFileEntry[] nested = { globalPool.getConstantPoolEntry(getPoolID(), offset) };
        Objects.requireNonNull(nested[0], "Null nested entries are not allowed");
        byteCode.setNested(nested);
        byteCode.setNestedPositions(new int[][] { { 0, 2 } });
    }
}
