/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen.asm;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.tools.WideningCategories;
import org.codehaus.groovy.classgen.AsmClassGenerator;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation;
import org.codehaus.groovy.runtime.typehandling.ShortTypeHandling;
import org.codehaus.groovy.syntax.SyntaxException;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

import static org.apache.groovy.ast.tools.ExpressionUtils.isNullConstant;
import static org.apache.groovy.ast.tools.ExpressionUtils.isSuperExpression;
import static org.apache.groovy.ast.tools.ExpressionUtils.isThisExpression;
import static org.codehaus.groovy.ast.ClassHelper.isClassType;
import static org.codehaus.groovy.ast.ClassHelper.isFunctionalInterface;
import static org.codehaus.groovy.ast.ClassHelper.isObjectType;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveType;
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveVoid;
import static org.codehaus.groovy.ast.ClassHelper.isStringType;
import static org.codehaus.groovy.transform.stc.StaticTypeCheckingSupport.isClassClassNodeWrappingConcreteType;
import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.DUP2_X1;
import static org.objectweb.asm.Opcodes.DUP_X1;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.SWAP;

public class InvocationWriter {

    // method invocation
    public static final MethodCallerMultiAdapter invokeMethodOnCurrent = MethodCallerMultiAdapter.newStatic(ScriptBytecodeAdapter.class, "invokeMethodOnCurrent", true, false);
    public static final MethodCallerMultiAdapter invokeMethodOnSuper = MethodCallerMultiAdapter.newStatic(ScriptBytecodeAdapter.class, "invokeMethodOnSuper", true, false);
    public static final MethodCallerMultiAdapter invokeMethod = MethodCallerMultiAdapter.newStatic(ScriptBytecodeAdapter.class, "invokeMethod", true, false);
    public static final MethodCallerMultiAdapter invokeStaticMethod = MethodCallerMultiAdapter.newStatic(ScriptBytecodeAdapter.class, "invokeStaticMethod", true, true);
    public static final MethodCaller invokeClosureMethod = MethodCaller.newStatic(ScriptBytecodeAdapter.class, "invokeClosure");
    public static final MethodCaller castToVargsArray = MethodCaller.newStatic(DefaultTypeTransformation.class, "castToVargsArray");
    private static final MethodNode CLASS_FOR_NAME_STRING = ClassHelper.CLASS_Type.getDeclaredMethod("forName", new Parameter[]{new Parameter(ClassHelper.STRING_TYPE, "name")});

    // type conversions
    private static final MethodCaller asTypeMethod = MethodCaller.newStatic(ScriptBytecodeAdapter.class, "asType");
    private static final MethodCaller castToTypeMethod = MethodCaller.newStatic(ScriptBytecodeAdapter.class, "castToType");
    private static final MethodCaller castToClassMethod = MethodCaller.newStatic(ShortTypeHandling.class, "castToClass");
    private static final MethodCaller castToStringMethod = MethodCaller.newStatic(ShortTypeHandling.class, "castToString");
    private static final MethodCaller castToEnumMethod = MethodCaller.newStatic(ShortTypeHandling.class, "castToEnum");

    // constructor calls with this() and super()
    private static final MethodCaller selectConstructorAndTransformArguments = MethodCaller.newStatic(ScriptBytecodeAdapter.class, "selectConstructorAndTransformArguments");

    protected final WriterController controller;

    public InvocationWriter(final WriterController controller) {
        this.controller = controller;
    }

    public void makeCall(final Expression origin, final Expression receiver, final Expression message, final Expression arguments, final MethodCallerMultiAdapter adapter, boolean safe, final boolean spreadSafe, boolean implicitThis) {
        ClassNode sender;
        if (isSuperExpression(receiver) || isThisExpression(receiver) && !implicitThis) {
            // GROOVY-6045, GROOVY-8693, et al.
            sender = controller.getThisType();
            implicitThis = false;
            safe = false;
        } else {
            sender = controller.getClassNode();
        }
        makeCall(origin, new ClassExpression(sender), receiver, message, arguments, adapter, safe, spreadSafe, implicitThis);
    }

    protected void makeCall(final Expression origin, final ClassExpression sender, final Expression receiver, final Expression message, final Expression arguments, final MethodCallerMultiAdapter adapter, final boolean safe, final boolean spreadSafe, final boolean implicitThis) {
        boolean containsSpreadExpression = AsmClassGenerator.containsSpreadExpression(arguments);
        // direct invocation path
        if (!makeDirectCall(origin, receiver, message, arguments, adapter, implicitThis, containsSpreadExpression))
            // call site or indy path
            if (!makeCachedCall(origin, sender, receiver, message, arguments, adapter, safe, spreadSafe, implicitThis, containsSpreadExpression))
                // ScriptBytecodeAdapter path
                makeUncachedCall(origin, sender, receiver, message, arguments, adapter, safe, spreadSafe, implicitThis, containsSpreadExpression);
    }

    protected boolean writeDirectMethodCall(final MethodNode target, final boolean implicitThis, final Expression receiver, final TupleExpression args) {
        if (target == null) return false;
        ClassNode declaringClass = target.getDeclaringClass();
        ClassNode enclosingClass = controller.getClassNode(), receiverType = enclosingClass;
        if (receiver != null) {
            receiverType = controller.getTypeChooser().resolveType(receiver, enclosingClass);
            if (target.isStatic() && isClassClassNodeWrappingConcreteType(receiverType)) {
                receiverType = receiverType.getGenericsTypes()[0].getType();
            }
        }

        CompileStack compileStack = controller.getCompileStack();
        OperandStack operandStack = controller.getOperandStack();
        MethodVisitor mv = controller.getMethodVisitor();
        int startDepth = operandStack.getStackLength();

        // handle receiver
        if (!target.isStatic()) {
            if (receiver != null) {
                Expression objectExpression = receiver;
                if (implicitThis
                        && enclosingClass.getOuterClass() != null
                        && !enclosingClass.isDerivedFrom(declaringClass)
                        && !enclosingClass.implementsInterface(declaringClass)) {
                    // outer class method invocation
                    compileStack.pushImplicitThis(false);
                    if (!controller.isInGeneratedFunction() && isThis(receiver)) {
                        objectExpression = new PropertyExpression(new ClassExpression(declaringClass), "this");
                    }
                } else {
                    compileStack.pushImplicitThis(implicitThis);
                }
                objectExpression.visit(controller.getAcg());
                operandStack.doGroovyCast(declaringClass);
                compileStack.popImplicitThis();
            } else {
                mv.visitIntInsn(ALOAD, 0);
                operandStack.push(enclosingClass);
            }
        }

        int opcode;
        if (target.isStatic()) {
            opcode = INVOKESTATIC;
        } else if (isSuperExpression(receiver)) {
            opcode = INVOKESPECIAL;
        } else if (declaringClass.isInterface()) {
            opcode = INVOKEINTERFACE;
        } else {
            opcode = INVOKEVIRTUAL;
        }

        ClassNode ownerClass = declaringClass;
        if (opcode == INVOKESPECIAL) { // GROOVY-8693, GROOVY-9909
            if (!declaringClass.isInterface() || receiverType.implementsInterface(declaringClass)) ownerClass = receiverType;
        } else if (opcode == INVOKEVIRTUAL && isObjectType(declaringClass)) {
            // avoid using a narrowed type if the method is defined on Object, because it can interfere
            // with delegate type inference in static compilation mode and trigger a ClassCastException
        } else if (opcode == INVOKEVIRTUAL
                && !receiverType.isArray()
                && !receiverType.isInterface()
                && !isPrimitiveType(receiverType)
                && !receiverType.equals(declaringClass)
                && receiverType.isDerivedFrom(declaringClass)) {
            ownerClass = receiverType; // use actual for typical call
            if (!receiverType.equals(operandStack.getTopOperand())) {
                mv.visitTypeInsn(CHECKCAST, BytecodeHelper.getClassInternalName(ownerClass));
            }
        } else if ((declaringClass.getModifiers() & (ACC_FINAL | ACC_PUBLIC)) == 0 && !receiverType.equals(declaringClass)
                && (declaringClass.isInterface() ? receiverType.implementsInterface(declaringClass) : receiverType.isDerivedFrom(declaringClass))) {
            // GROOVY-6962, GROOVY-9955, GROOVY-10380: method declared by inaccessible class or interface
            if (declaringClass.isInterface() && !receiverType.isInterface()) opcode = INVOKEVIRTUAL;
            ownerClass = receiverType;
        }

        loadArguments(args.getExpressions(), target.getParameters());

        String ownerName = BytecodeHelper.getClassInternalName(ownerClass), methodName = target.getName();
        String signature = BytecodeHelper.getMethodDescriptor(target.getReturnType(), target.getParameters());
        mv.visitMethodInsn(opcode, ownerName, methodName, signature, ownerClass.isInterface());
        ClassNode returnType = target.getReturnType();
        if (isPrimitiveVoid(returnType)) {
            returnType = ClassHelper.OBJECT_TYPE;
            mv.visitInsn(ACONST_NULL);
        }
        // replace the method call's receiver and argument types with the return type
        operandStack.replace(returnType, operandStack.getStackLength() - startDepth);
        return true;
    }

    /**
     * Supplements {@link org.apache.groovy.ast.tools.ExpressionUtils#isThisExpression isThisExpression}
     * with the ability to see into {@code CheckcastReceiverExpression}.
     */
    private static boolean isThis(final Expression expression) {
        boolean[] isThis = new boolean[1];
        expression.visit(new org.codehaus.groovy.ast.GroovyCodeVisitorAdapter() {
            @Override
            public void visitVariableExpression(final VariableExpression vexp) {
                isThis[0] = vexp.isThisExpression();
            }
        });
        return isThis[0];
    }

    private boolean lastIsArray(final List<Expression> argumentList, final int pos) {
        Expression last = argumentList.get(pos);
        ClassNode type = controller.getTypeChooser().resolveType(last, controller.getClassNode());
        return type.isArray();
    }

    // load arguments
    protected void loadArguments(final List<Expression> argumentList, final Parameter[] para) {
        if (para.length == 0) return;
        ClassNode lastParaType = para[para.length - 1].getOriginType();
        AsmClassGenerator acg = controller.getAcg();
        OperandStack operandStack = controller.getOperandStack();
        if (lastParaType.isArray() && (argumentList.size() > para.length
                || argumentList.size() == para.length - 1 || !lastIsArray(argumentList, para.length - 1))) {
            int stackLen = operandStack.getStackLength() + argumentList.size();
            MethodVisitor mv = controller.getMethodVisitor();
            controller.setMethodVisitor(mv);
            // varg call
            // first parameters as usual
            for (int i = 0, n = para.length - 1; i < n; i += 1) {
                argumentList.get(i).visit(acg);
                operandStack.doGroovyCast(para[i].getType());
            }
            // last parameters wrapped in an array
            List<Expression> lastParams = new LinkedList<>();
            for (int i = para.length - 1, n = argumentList.size(); i < n; i += 1) {
                lastParams.add(argumentList.get(i));
            }
            ArrayExpression array = new ArrayExpression(
                    lastParaType.getComponentType(),
                    lastParams
            );
            array.visit(acg);
            // adjust stack length
            while (operandStack.getStackLength() < stackLen) {
                operandStack.push(ClassHelper.OBJECT_TYPE);
            }
            if (argumentList.size() == para.length - 1) {
                operandStack.remove(1);
            }
        } else {
            for (int i = 0, n = argumentList.size(); i < n; i += 1) {
                argumentList.get(i).visit(acg);
                operandStack.doGroovyCast(para[i].getType());
            }
        }
    }

    protected boolean makeDirectCall(Expression origin, Expression receiver, Expression message, Expression arguments, MethodCallerMultiAdapter adapter, boolean implicitThis, boolean containsSpreadExpression) {
        if (makeClassForNameCall(origin, receiver, message, arguments)) return true;
        if (controller.optimizeForInt && controller.isFastPath() // optimization path
                && adapter == invokeMethodOnCurrent || adapter == invokeStaticMethod) {
            String methodName = getMethodName(message);
            if (methodName != null) {
                OptimizingStatementWriter.StatementMeta meta = origin.getNodeMetaData(OptimizingStatementWriter.StatementMeta.class);
                if (meta != null && meta.target != null) {
                    TupleExpression args;
                    if (arguments instanceof TupleExpression) {
                        args = (TupleExpression) arguments;
                    } else {
                        args = new TupleExpression(receiver);
                    }
                    if (writeDirectMethodCall(meta.target, true, null, args)) return true;
                }
            }
        }
        if (containsSpreadExpression) return false;
        if (origin instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) origin;
            if (mce.getMethodTarget() != null)
                return writeDirectMethodCall(mce.getMethodTarget(), implicitThis, receiver, makeArgumentList(arguments));
        }
        return false;
    }

    protected boolean makeCachedCall(Expression origin, ClassExpression sender, Expression receiver, Expression message, Expression arguments, MethodCallerMultiAdapter adapter, boolean safe, boolean spreadSafe, boolean implicitThis, boolean containsSpreadExpression) {
        // prepare call site
        if ((adapter == invokeMethod || adapter == invokeMethodOnCurrent || adapter == invokeStaticMethod) && !spreadSafe) {
            String methodName = getMethodName(message);
            if (methodName != null) {
                controller.getCallSiteWriter().makeCallSite(receiver, methodName, arguments, safe, implicitThis, adapter == invokeMethodOnCurrent, adapter == invokeStaticMethod);
                return true;
            }
        }
        return false;
    }

    protected void makeUncachedCall(Expression origin, ClassExpression sender, Expression receiver, Expression message, Expression arguments, MethodCallerMultiAdapter adapter, boolean safe, boolean spreadSafe, boolean implicitThis, boolean containsSpreadExpression) {
        CompileStack compileStack = controller.getCompileStack();
        OperandStack operandStack = controller.getOperandStack();
        AsmClassGenerator acg = controller.getAcg();

        // ensure VariableArguments are read, not stored
        compileStack.pushLHS(false);

        // sender only for call sites
        if (adapter == AsmClassGenerator.setProperty) {
            ConstantExpression.NULL.visit(acg);
        } else {
            sender.visit(acg);
        }

        String methodName = getMethodName(message);
        if (adapter == invokeMethodOnSuper && methodName != null) {
            controller.getSuperMethodNames().add(methodName); // for MOP method
        }

        // receiver
        compileStack.pushImplicitThis(implicitThis);
        receiver.visit(acg);
        operandStack.box();
        compileStack.popImplicitThis();

        int operandsToRemove = 2;
        // message
        if (message != null) {
            message.visit(acg);
            operandStack.box();
            operandsToRemove += 1;
        }

        // arguments
        int numberOfArguments = containsSpreadExpression ? -1 : AsmClassGenerator.argumentSize(arguments);
        if (numberOfArguments > MethodCallerMultiAdapter.MAX_ARGS || containsSpreadExpression) {
            ArgumentListExpression ae = makeArgumentList(arguments);
            if (containsSpreadExpression) {
                acg.despreadList(ae.getExpressions(), true);
            } else {
                ae.visit(acg);
            }
        } else if (numberOfArguments > 0) {
            operandsToRemove += numberOfArguments;
            TupleExpression te = (TupleExpression) arguments;
            for (int i = 0; i < numberOfArguments; i += 1) {
                Expression argument = te.getExpression(i);
                argument.visit(acg);
                operandStack.box();
                if (argument instanceof CastExpression) acg.loadWrapper(argument);
            }
        }

        if (adapter == null) adapter = invokeMethod;
        adapter.call(controller.getMethodVisitor(), numberOfArguments, safe, spreadSafe);

        compileStack.popLHS();
        operandStack.replace(ClassHelper.OBJECT_TYPE, operandsToRemove);
    }

    /**
     * if Class.forName(x) is recognized, make a direct method call
     */
    protected boolean makeClassForNameCall(final Expression origin, final Expression receiver, final Expression message, final Expression arguments) {
        if (!(receiver instanceof ClassExpression)) return false;
        ClassExpression ce = (ClassExpression) receiver;
        if (!ClassHelper.CLASS_Type.equals(ce.getType())) return false;
        String msg = getMethodName(message);
        if (!"forName".equals(msg)) return false;
        ArgumentListExpression ae = makeArgumentList(arguments);
        if (ae.getExpressions().size() != 1) return false;
        return writeDirectMethodCall(CLASS_FOR_NAME_STRING, false, receiver, ae);
    }

    public static ArgumentListExpression makeArgumentList(final Expression arguments) {
        ArgumentListExpression ae;
        if (arguments instanceof ArgumentListExpression) {
            ae = (ArgumentListExpression) arguments;
        } else if (arguments instanceof TupleExpression) {
            TupleExpression te = (TupleExpression) arguments;
            ae = new ArgumentListExpression(te.getExpressions());
        } else {
            ae = new ArgumentListExpression();
            ae.addExpression(arguments);
        }
        return ae;
    }

    protected String getMethodName(final Expression message) {
        String methodName = null;
        if (message instanceof CastExpression) {
            CastExpression msg = (CastExpression) message;
            if (isStringType(msg.getType())) {
                final Expression methodExpr = msg.getExpression();
                if (methodExpr instanceof ConstantExpression) {
                    methodName = methodExpr.getText();
                }
            }
        }

        if (methodName == null && message instanceof ConstantExpression) {
            ConstantExpression constantExpression = (ConstantExpression) message;
            methodName = constantExpression.getText();
        }
        return methodName;
    }

    public void writeInvokeMethod(MethodCallExpression call) {
        if (isClosureCall(call)) {
            invokeClosure(call.getArguments(), call.getMethodAsString());
        } else {
            if (isFunctionInterfaceCall(call)) {
                call = transformToRealMethodCall(call);
            }
            Expression receiver = call.getObjectExpression();
            MethodCallerMultiAdapter adapter = invokeMethod;
            if (isSuperExpression(receiver)) {
                adapter = invokeMethodOnSuper;
            } else if (isThisExpression(receiver)) {
                adapter = invokeMethodOnCurrent;
            }
            if (isStaticInvocation(call)) {
                adapter = invokeStaticMethod;
            }
            Expression messageName = new CastExpression(ClassHelper.STRING_TYPE, call.getMethod());
            makeCall(call, receiver, messageName, call.getArguments(), adapter, call.isSafe(), call.isSpreadSafe(), call.isImplicitThis());
        }
    }

    private static boolean isFunctionInterfaceCall(final MethodCallExpression call) {
        if ("call".equals(call.getMethodAsString())) {
            Expression objectExpression = call.getObjectExpression();
            if (!isThisExpression(objectExpression)) {
                return isFunctionalInterface(objectExpression.getType());
            }
        }
        return false;
    }

    private static MethodCallExpression transformToRealMethodCall(MethodCallExpression call) {
        ClassNode type = call.getObjectExpression().getType();
        MethodNode methodNode = ClassHelper.findSAM(type);

        call = (MethodCallExpression) call.transformExpression(expression -> {
            if (!(expression instanceof ConstantExpression)) {
                return expression;
            }
            return new ConstantExpression(methodNode.getName());
        });
        call.setMethodTarget(methodNode);
        return call;
    }

    private boolean isClosureCall(final MethodCallExpression call) {
        // are we a local variable?
        // it should not be an explicitly "this" qualified method call
        // and the current class should have a possible method
        ClassNode classNode = controller.getClassNode();
        String methodName = call.getMethodAsString();
        if (methodName == null) return false;
        if (!call.isImplicitThis()) return false;
        if (!isThisExpression(call.getObjectExpression())) return false;
        FieldNode field = classNode.getDeclaredField(methodName);
        if (field == null) return false;
        if (isStaticInvocation(call) && !field.isStatic()) return false;
        Expression arguments = call.getArguments();
        return !classNode.hasPossibleMethod(methodName, arguments);
    }

    private void invokeClosure(final Expression arguments, final String methodName) {
        AsmClassGenerator acg = controller.getAcg();
        acg.visitVariableExpression(new VariableExpression(methodName));
        controller.getOperandStack().box();
        if (arguments instanceof TupleExpression) {
            arguments.visit(acg);
        } else {
            new TupleExpression(arguments).visit(acg);
        }
        invokeClosureMethod.call(controller.getMethodVisitor());
        controller.getOperandStack().replace(ClassHelper.OBJECT_TYPE);
    }

    private boolean isStaticInvocation(final MethodCallExpression call) {
        if (!isThisExpression(call.getObjectExpression())) return false;
        if (controller.isStaticMethod()) return true;
        return controller.isStaticContext() && !call.isImplicitThis();
    }

    public void writeInvokeStaticMethod(final StaticMethodCallExpression call) {
        Expression receiver = new ClassExpression(call.getOwnerType());
        Expression messageName = new ConstantExpression(call.getMethod());
        makeCall(call, receiver, messageName, call.getArguments(), InvocationWriter.invokeStaticMethod, false, false, false);
    }

    private boolean writeDirectConstructorCall(final ConstructorCallExpression call) {
        if (!controller.isFastPath()) return false;

        OptimizingStatementWriter.StatementMeta meta = call.getNodeMetaData(OptimizingStatementWriter.StatementMeta.class);
        ConstructorNode cn = null;
        if (meta != null) cn = (ConstructorNode) meta.target;
        if (cn == null) return false;

        String ownerDescriptor = prepareConstructorCall(cn);
        TupleExpression args = makeArgumentList(call.getArguments());
        loadArguments(args.getExpressions(), cn.getParameters());
        finnishConstructorCall(cn, ownerDescriptor, args.getExpressions().size());

        return true;
    }

    protected String prepareConstructorCall(final ConstructorNode cn) {
        String owner = BytecodeHelper.getClassInternalName(cn.getDeclaringClass());
        MethodVisitor mv = controller.getMethodVisitor();
        mv.visitTypeInsn(NEW, owner);
        mv.visitInsn(DUP);
        return owner;
    }

    protected void finnishConstructorCall(final ConstructorNode cn, final String ownerDescriptor, final int argsToRemove) {
        String desc = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, cn.getParameters());
        MethodVisitor mv = controller.getMethodVisitor();
        mv.visitMethodInsn(INVOKESPECIAL, ownerDescriptor, "<init>", desc, false);

        controller.getOperandStack().remove(argsToRemove);
        controller.getOperandStack().push(cn.getDeclaringClass());
    }

    protected void writeNormalConstructorCall(final ConstructorCallExpression call) {
        Expression arguments = call.getArguments();
        if (arguments instanceof TupleExpression) {
            TupleExpression tupleExpression = (TupleExpression) arguments;
            int size = tupleExpression.getExpressions().size();
            if (size == 0) {
                arguments = MethodCallExpression.NO_ARGUMENTS;
            }
        }

        Expression receiver = new ClassExpression(call.getType());
        controller.getCallSiteWriter().makeCallSite(receiver, CallSiteWriter.CONSTRUCTOR, arguments, false, false, false, false);
    }

    public void writeInvokeConstructor(final ConstructorCallExpression call) {
        if (writeDirectConstructorCall(call)) return;
        if (writeAICCall(call)) return;
        writeNormalConstructorCall(call);
    }

    protected boolean writeAICCall(final ConstructorCallExpression call) {
        if (!call.isUsingAnonymousInnerClass()) return false;
        ConstructorNode cn = call.getType().getDeclaredConstructors().get(0);
        OperandStack os = controller.getOperandStack();

        String ownerDescriptor = prepareConstructorCall(cn);

        List<Expression> args = makeArgumentList(call.getArguments()).getExpressions();
        Parameter[] params = cn.getParameters();
        // if a this appears as parameter here, then it should be
        // not static, unless we are in a static method. But since
        // ACG#visitVariableExpression does the opposite for this case, we
        // push here an explicit this. This should not have any negative effect
        // sine visiting a method call or property with implicit this will push
        // a new value for this again.
        controller.getCompileStack().pushImplicitThis(true);
        for (int i = 0, n = params.length; i < n; i += 1) {
            Parameter p = params[i];
            Expression arg = args.get(i);
            if (arg instanceof VariableExpression) {
                VariableExpression var = (VariableExpression) arg;
                loadVariableWithReference(var);
            } else {
                arg.visit(controller.getAcg());
            }
            os.doGroovyCast(p.getType());
        }
        controller.getCompileStack().popImplicitThis();
        finnishConstructorCall(cn, ownerDescriptor, args.size());
        return true;
    }

    private void loadVariableWithReference(final VariableExpression var) {
        if (!var.isUseReferenceDirectly()) {
            var.visit(controller.getAcg());
        } else {
            ClosureWriter.loadReference(var.getName(), controller);
        }
    }

    public final void makeSingleArgumentCall(final Expression receiver, final String message, final Expression arguments) {
        makeSingleArgumentCall(receiver, message, arguments, false);
    }

    public void makeSingleArgumentCall(final Expression receiver, final String message, final Expression arguments, final boolean safe) {
        controller.getCallSiteWriter().makeSingleArgumentCall(receiver, message, arguments, safe);
    }

    public void writeSpecialConstructorCall(final ConstructorCallExpression call) {
        controller.getCompileStack().pushInSpecialConstructorCall();
        visitSpecialConstructorCall(call);
        controller.getCompileStack().pop();
    }

    private void visitSpecialConstructorCall(final ConstructorCallExpression call) {
        if (controller.getClosureWriter().addGeneratedClosureConstructorCall(call)) return;
        ClassNode callNode = controller.getClassNode();
        if (call.isSuperCall()) callNode = callNode.getSuperClass();
        List<ConstructorNode> constructors = sortConstructors(call, callNode);
        if (!makeDirectConstructorCall(constructors, call, callNode)) {
            makeMOPBasedConstructorCall(constructors, call, callNode);
        }
    }

    private static List<ConstructorNode> sortConstructors(final ConstructorCallExpression call, final ClassNode callNode) {
        // sort in a new list to prevent side effects
        List<ConstructorNode> constructors = new ArrayList<>(callNode.getDeclaredConstructors());
        constructors.sort((c0, c1) -> {
            String descriptor0 = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, c0.getParameters());
            String descriptor1 = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, c1.getParameters());
            return descriptor0.compareTo(descriptor1);
        });
        return constructors;
    }

    private boolean makeDirectConstructorCall(final List<ConstructorNode> constructors, final ConstructorCallExpression call, final ClassNode callNode) {
        if (!controller.isConstructor()) return false;

        Expression arguments = call.getArguments();
        List<Expression> argumentList;
        if (arguments instanceof TupleExpression) {
            argumentList = ((TupleExpression) arguments).getExpressions();
        } else {
            argumentList = new ArrayList<>();
            argumentList.add(arguments);
        }
        for (Expression expression : argumentList) {
            if (expression instanceof SpreadExpression) return false;
        }

        ConstructorNode cn = getMatchingConstructor(constructors, argumentList);
        if (cn == null) return false;
        MethodVisitor mv = controller.getMethodVisitor();
        OperandStack operandStack = controller.getOperandStack();
        Parameter[] params = cn.getParameters();

        mv.visitVarInsn(ALOAD, 0);
        for (int i = 0, n = params.length; i < n; i += 1) {
            Expression expression = argumentList.get(i);
            expression.visit(controller.getAcg());
            if (!isNullConstant(expression)) {
                operandStack.doGroovyCast(params[i].getType());
            }
            operandStack.remove(1);
        }
        String descriptor = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, params);
        mv.visitMethodInsn(INVOKESPECIAL, BytecodeHelper.getClassInternalName(callNode), "<init>", descriptor, false);

        return true;
    }

    private void makeMOPBasedConstructorCall(final List<ConstructorNode> constructors, final ConstructorCallExpression call, final ClassNode callNode) {
        MethodVisitor mv = controller.getMethodVisitor();
        OperandStack operandStack = controller.getOperandStack();
        call.getArguments().visit(controller.getAcg());
        // keep Object[] on stack
        mv.visitInsn(DUP);
        // to select the constructor we need also the number of
        // available constructors and the class we want to make
        // the call on
        BytecodeHelper.pushConstant(mv, -1);
        controller.getAcg().visitClassExpression(new ClassExpression(callNode));
        operandStack.remove(1);
        // removes one Object[] leaves the int containing the
        // call flags and the constructor number
        selectConstructorAndTransformArguments.call(mv);
        //load "this"
        if (controller.isConstructor()) {
            mv.visitVarInsn(ALOAD, 0);
        } else {
            mv.visitTypeInsn(NEW, BytecodeHelper.getClassInternalName(callNode));
        }
        mv.visitInsn(SWAP);
        TreeMap<Integer,ConstructorNode> sortedConstructors = new TreeMap<>();
        for (ConstructorNode constructor : constructors) {
            String typeDescriptor = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, constructor.getParameters());
            int hash = BytecodeHelper.hashCode(typeDescriptor);
            ConstructorNode sameHashNode = sortedConstructors.put(hash, constructor);
            if (sameHashNode != null) {
                controller.getSourceUnit().addError(new SyntaxException(
                    "Unable to compile class "+controller.getClassNode().getName() + " due to hash collision in constructors", call.getLineNumber(), call.getColumnNumber()));
            }
        }
        Label[] targets = new Label[constructors.size()];
        int[] indices = new int[constructors.size()];
        Iterator<Integer> hashIt = sortedConstructors.keySet().iterator();
        Iterator<ConstructorNode> constructorIt = sortedConstructors.values().iterator();
        for (int i = 0, n = targets.length; i < n; i += 1) {
            targets[i] = new Label();
            indices[i] = hashIt.next();
        }

        // create switch targets
        Label defaultLabel = new Label();
        Label afterSwitch = new Label();
        mv.visitLookupSwitchInsn(defaultLabel, indices, targets);
        for (Label target : targets) {
            mv.visitLabel(target);
            // to keep the stack height, we need to leave
            // one Object[] on the stack as last element. At the
            // same time, we need the Object[] on top of the stack
            // to extract the parameters.
            if (controller.isConstructor()) {
                // in this case we need one "this", so a SWAP will exchange
                // "this" and Object[], a DUP_X1 will then copy the Object[]
                /// to the last place in the stack:
                //     Object[],this -SWAP-> this,Object[]
                //     this,Object[] -DUP_X1-> Object[],this,Object[]
                mv.visitInsn(SWAP);
                mv.visitInsn(DUP_X1);
            } else {
                // in this case we need two "this" in between and the Object[]
                // at the bottom of the stack as well as on top for our invokeSpecial
                // So we do DUP_X1, DUP2_X1, POP
                //     Object[],this -DUP_X1-> this,Object[],this
                //     this,Object[],this -DUP2_X1-> Object[],this,this,Object[],this
                //     Object[],this,this,Object[],this -POP->  Object[],this,this,Object[]
                mv.visitInsn(DUP_X1);
                mv.visitInsn(DUP2_X1);
                mv.visitInsn(POP);
            }

            ConstructorNode cn = constructorIt.next();
            String descriptor = BytecodeHelper.getMethodDescriptor(ClassHelper.VOID_TYPE, cn.getParameters());

            // unwrap the Object[] and make transformations if needed
            // that means, to duplicate the Object[], make a cast with possible
            // unboxing and then swap it with the Object[] for each parameter
            // vargs need special attention and transformation though
            Parameter[] parameters = cn.getParameters();
            int lengthWithoutVargs = parameters.length;
            if (parameters.length > 0 && parameters[parameters.length - 1].getType().isArray()) {
                lengthWithoutVargs -= 1;
            }
            for (int p = 0; p < lengthWithoutVargs; p += 1) {
                loadAndCastElement(operandStack, mv, parameters, p);
            }
            if (parameters.length > lengthWithoutVargs) {
                ClassNode type = parameters[lengthWithoutVargs].getType();
                BytecodeHelper.pushConstant(mv, lengthWithoutVargs);
                controller.getAcg().visitClassExpression(new ClassExpression(type));
                operandStack.remove(1);
                castToVargsArray.call(mv);
                BytecodeHelper.doCast(mv, type);
            } else {
                // at the end we remove the Object[]
                // the vargs case simply the last swap so no pop is needed
                mv.visitInsn(POP);
            }
            // make the constructor call
            mv.visitMethodInsn(INVOKESPECIAL, BytecodeHelper.getClassInternalName(callNode), "<init>", descriptor, false);
            mv.visitJumpInsn(GOTO, afterSwitch);
        }
        mv.visitLabel(defaultLabel);
        // this part should never be reached!
        mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("This class has been compiled with a super class which is binary incompatible with the current super class found on classpath. You should recompile this class with the new version.");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(afterSwitch);

        // For a special constructor call inside a constructor we don't need
        // any result object on the stack, for outside the constructor we do.
        // to keep the stack height for the able we kept one object as dummy
        // result on the stack, which we can remove now if inside a constructor.
        if (!controller.isConstructor()) {
            // in case we are not in a constructor we have an additional
            // object on the stack, the result of our constructor call
            // which we want to keep, so we swap with the dummy object and
            // do normal removal of it. In the end, the call result will be
            // on the stack then
            mv.visitInsn(SWAP);
            operandStack.push(callNode); // for call result
        }
        mv.visitInsn(POP);
    }

    private static void loadAndCastElement(final OperandStack operandStack, final MethodVisitor mv, final Parameter[] parameters, final int p) {
        operandStack.push(ClassHelper.OBJECT_TYPE);
        mv.visitInsn(DUP);
        BytecodeHelper.pushConstant(mv, p);
        mv.visitInsn(AALOAD);
        operandStack.push(ClassHelper.OBJECT_TYPE);
        ClassNode type = parameters[p].getType();
        operandStack.doGroovyCast(type);
        operandStack.swap();
        operandStack.remove(2);
    }

    // we match only on the number of arguments, not anything else
    private static ConstructorNode getMatchingConstructor(final List<ConstructorNode> constructors, final List<Expression> argumentList) {
        ConstructorNode lastMatch = null;
        for (ConstructorNode cn : constructors) {
            Parameter[] params = cn.getParameters();
            // if number of parameters does not match we have no match
            if (argumentList.size() != params.length) continue;
            if (lastMatch == null) {
                lastMatch = cn;
            } else {
                // we already had a match so we don't make a direct call at all
                return null;
            }
        }
        return lastMatch;
    }

    /**
     * Converts sourceType to a non primitive by using Groovy casting.
     * sourceType might be a primitive
     * This might be done using SBA#castToType
     */
    public void castToNonPrimitiveIfNecessary(final ClassNode sourceType, final ClassNode targetType) {
        OperandStack os = controller.getOperandStack();
        ClassNode boxedType = os.box();
        if (WideningCategories.implementsInterfaceOrSubclassOf(boxedType, targetType)) return;
        MethodVisitor mv = controller.getMethodVisitor();
        if (isClassType(targetType)) {
            castToClassMethod.call(mv);
        } else if (isStringType(targetType)) {
            castToStringMethod.call(mv);
        } else if (targetType.isDerivedFrom(ClassHelper.Enum_Type)) {
            (new ClassExpression(targetType)).visit(controller.getAcg());
            os.remove(1);
            castToEnumMethod.call(mv);
            BytecodeHelper.doCast(mv, targetType);
        } else {
            (new ClassExpression(targetType)).visit(controller.getAcg());
            os.remove(1);
            castToTypeMethod.call(mv);
        }
    }

    public void castNonPrimitiveToBool(final ClassNode last) {
        MethodVisitor mv = controller.getMethodVisitor();
        BytecodeHelper.unbox(mv, ClassHelper.boolean_TYPE);
    }

    public void coerce(final ClassNode from, final ClassNode target) {
        if (from.isDerivedFrom(target)) return;
        MethodVisitor mv = controller.getMethodVisitor();
        OperandStack os = controller.getOperandStack();
        os.box();
        (new ClassExpression(target)).visit(controller.getAcg());
        os.remove(1);
        asTypeMethod.call(mv);
        BytecodeHelper.doCast(mv,target);
        os.replace(target);
    }
}
