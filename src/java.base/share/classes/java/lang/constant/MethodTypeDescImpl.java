/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.lang.constant;

import jdk.internal.access.JavaUtilCollectionAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.annotation.Stable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.lang.constant.ConstantUtils.*;
import static java.util.Objects.requireNonNull;

/**
 * A <a href="package-summary.html#nominal">nominal descriptor</a> for a
 * {@link MethodType}.  A {@linkplain MethodTypeDescImpl} corresponds to a
 * {@code Constant_MethodType_info} entry in the constant pool of a classfile.
 */
final class MethodTypeDescImpl implements MethodTypeDesc {
    private static final JavaUtilCollectionAccess JUCA = SharedSecrets.getJavaUtilCollectionAccess();

    private final ClassDesc returnType;
    private final List<ClassDesc> argTypes;
    private @Stable String cachedDescriptorString;

    /**
     * Constructs a {@linkplain MethodTypeDesc} with the specified return type
     * and parameter types. Performs a check over parameter types to ensure their
     * validity.
     *
     * @param returnType a {@link ClassDesc} describing the return type
     * @param argTypes a list of {@link ClassDesc}s describing the parameter types
     */
    MethodTypeDescImpl(ClassDesc returnType, List<ClassDesc> argTypes) {
        this.returnType = requireNonNull(returnType);
        this.argTypes = List.copyOf(argTypes); // implicit null-check

        for (ClassDesc cr : this.argTypes)
            if (cr.isPrimitive() && cr.descriptorString().charAt(0) == 'V')
                throw new IllegalArgumentException("Void parameters not permitted");
    }

    /**
     * Creates a {@linkplain MethodTypeDescImpl} given a method descriptor string.
     *
     * @param descriptor the method descriptor string
     * @return a {@linkplain MethodTypeDescImpl} describing the desired method type
     * @throws IllegalArgumentException if the descriptor string is not a valid
     * method descriptor
     * @jvms 4.3.3 Method Descriptors
     */
    static MethodTypeDescImpl ofDescriptor(String descriptor) {
        int cur = 0, end = descriptor.length(); // implicit null check

        if (end == 0 || descriptor.charAt(0) != '(')
            throw new IllegalArgumentException("Bad method descriptor: " + descriptor);
        cur++; // skip '('
        if (cur >= end) {
            throw new IllegalArgumentException("Bad method descriptor: " + descriptor);
        }
        ArrayList<ClassDesc> ptypes;
        if (descriptor.charAt(cur) != ')') {
            ptypes = new ArrayList<>();
            do {
                int len = skipOverFieldSignature(descriptor, cur, end, false);
                if (len == 0)
                    throw new IllegalArgumentException("Bad method descriptor: " + descriptor);
                ptypes.add(ClassDesc.ofDescriptor(descriptor.substring(cur, cur + len)));
                cur += len;
                if (cur >= end) {
                    throw new IllegalArgumentException("Bad method descriptor: " + descriptor);
                }
            } while (descriptor.charAt(cur) != ')');
        } else {
            ptypes = null;
        }
        cur++; // skip ')'

        int rLen = skipOverFieldSignature(descriptor, cur, end, true);
        if (rLen == 0 || cur + rLen != descriptor.length())
            throw new IllegalArgumentException("Bad method descriptor: " + descriptor);

        ClassDesc retType = ClassDesc.ofDescriptor(descriptor.substring(cur));
        List<ClassDesc> types = ptypes == null ? List.of() : JUCA.listFromTrustedArray(ptypes.toArray());
        var ret = new MethodTypeDescImpl(retType, types);
        ret.cachedDescriptorString = descriptor;
        return ret;
    }

    @Override
    public ClassDesc returnType() {
        return returnType;
    }

    @Override
    public int parameterCount() {
        return argTypes.size();
    }

    @Override
    public ClassDesc parameterType(int index) {
        return argTypes.get(index);
    }

    @Override
    public List<ClassDesc> parameterList() {
        return argTypes;
    }

    @Override
    public ClassDesc[] parameterArray() {
        return argTypes.toArray(EMPTY_CLASSDESC);
    }

    @Override
    public MethodTypeDesc changeReturnType(ClassDesc returnType) {
        Objects.requireNonNull(returnType);

        return new MethodTypeDescImpl(returnType, argTypes);
    }

    @Override
    public MethodTypeDesc changeParameterType(int index, ClassDesc paramType) {
        Objects.checkIndex(index, argTypes.size());
        Objects.requireNonNull(paramType);

        Object[] newArgs = argTypes.toArray();
        newArgs[index] = paramType;
        return new MethodTypeDescImpl(returnType, JUCA.listFromTrustedArray(newArgs));
    }

    @Override
    public MethodTypeDesc dropParameterTypes(int start, int end) {
        Objects.checkFromToIndex(start, end, argTypes.size());
        if (start == end) return this;

        ClassDesc[] argTypes = this.argTypes.toArray(EMPTY_CLASSDESC);
        Object[] newArgs = new Object[argTypes.length - (end - start)];
        System.arraycopy(argTypes, 0, newArgs, 0, start);
        System.arraycopy(argTypes, end, newArgs, start, argTypes.length - end);
        return new MethodTypeDescImpl(returnType, JUCA.listFromTrustedArray(newArgs));
    }

    @Override
    public MethodTypeDesc insertParameterTypes(int pos, ClassDesc... paramTypes) {
        Objects.checkIndex(pos, argTypes.size() + 1);
        if (paramTypes.length == 0) return this; // Implicit null check

        Object[] newArgs = argTypes.toArray(new Object[argTypes.size() + paramTypes.length]);
        System.arraycopy(newArgs, pos, newArgs, pos + paramTypes.length, argTypes.size() - pos);
        System.arraycopy(paramTypes, 0, newArgs, pos, paramTypes.length);
        return new MethodTypeDescImpl(returnType, JUCA.listFromTrustedArray(newArgs));
    }

    @Override
    public String descriptorString() {
        var str = cachedDescriptorString;
        if (str != null)
            return str;

        return cachedDescriptorString = MethodTypeDesc.super.descriptorString();
    }

    @Override
    public MethodType resolveConstantDesc(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
        @SuppressWarnings("removal")
        MethodType mtype = AccessController.doPrivileged(new PrivilegedAction<>() {
            @Override
            public MethodType run() {
                return MethodType.fromMethodDescriptorString(descriptorString(),
                        lookup.lookupClass().getClassLoader());
            }
        });

        // let's check that the lookup has access to all the types in the method type
        lookup.accessClass(mtype.returnType());
        for (Class<?> paramType: mtype.parameterArray()) {
            lookup.accessClass(paramType);
        }
        return mtype;
    }

    /**
     * Returns {@code true} if this {@linkplain MethodTypeDescImpl} is
     * equal to another {@linkplain MethodTypeDescImpl}.  Equality is
     * determined by the two descriptors having equal return types and argument
     * types.
     *
     * @param o the {@code MethodTypeDescImpl} to compare to this
     *       {@code MethodTypeDescImpl}
     * @return {@code true} if the specified {@code MethodTypeDescImpl}
     *      is equal to this {@code MethodTypeDescImpl}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MethodTypeDescImpl constant = (MethodTypeDescImpl) o;

        return returnType.equals(constant.returnType)
               && Objects.equals(argTypes, constant.argTypes);
    }

    @Override
    public int hashCode() {
        int result = returnType.hashCode();
        result = 31 * result + Objects.hashCode(argTypes);
        return result;
    }

    @Override
    public String toString() {
        return String.format("MethodTypeDesc[%s]", displayDescriptor());
    }
}
