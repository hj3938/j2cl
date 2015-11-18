/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast.visitors;

import com.google.common.collect.Lists;
import com.google.j2cl.ast.AbstractRewriter;
import com.google.j2cl.ast.BinaryExpression;
import com.google.j2cl.ast.BinaryOperator;
import com.google.j2cl.ast.Block;
import com.google.j2cl.ast.CatchClause;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.ExpressionStatement;
import com.google.j2cl.ast.IfStatement;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.NullLiteral;
import com.google.j2cl.ast.Statement;
import com.google.j2cl.ast.ThrowStatement;
import com.google.j2cl.ast.TryStatement;
import com.google.j2cl.ast.TypeDescriptors;
import com.google.j2cl.ast.TypeDescriptors.BootstrapType;
import com.google.j2cl.ast.Variable;
import com.google.j2cl.ast.VariableDeclarationExpression;
import com.google.j2cl.ast.VariableDeclarationFragment;
import com.google.j2cl.ast.VariableDeclarationStatement;
import com.google.j2cl.ast.Visibility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Normalize Java try with resource blocks such that they can be represented in JavaScript.
 */
public class NormalizeTryWithResourceVisitor extends AbstractRewriter {
  public static void applyTo(CompilationUnit compilationUnit) {
    new NormalizeTryWithResourceVisitor().normalizeTryWithResourceBlocks(compilationUnit);
  }

  private void normalizeTryWithResourceBlocks(CompilationUnit compilationUnit) {
    compilationUnit.accept(this);
  }

  /**
   * We want to rewrite the try with resource statement to use vanilla try catch statements
   * as described by the JLS 14.20.3.1.
   */
  @Override
  public Statement rewriteTryStatement(TryStatement tryStatement) {
    if (tryStatement.getResourceDeclarations().isEmpty()) {
      return tryStatement;
    }
    if (tryStatement.getFinallyBlock() != null || !tryStatement.getCatchClauses().isEmpty()) {
      // See JLS 14.20.3.2
      TryStatement tryBlock =
          new TryStatement(
              tryStatement.getResourceDeclarations(),
              tryStatement.getBody(),
              new ArrayList<CatchClause>(),
              null);
      Block refactoredTryBlock = new Block(removeResourceDeclarations(tryBlock));
      return new TryStatement(
          new ArrayList<VariableDeclarationExpression>(),
          refactoredTryBlock,
          tryStatement.getCatchClauses(),
          tryStatement.getFinallyBlock());
    }
    return new Block(removeResourceDeclarations(tryStatement));
  }

  /**
   *
   * We transform:
   *
   * try (ClosableThing thing = new ClosableThing(), ClosableThing thing2 = new ClosableThing()) {
   *     ....
   *
   * to:
   *
   * let $primaryExc = null;
   * let thing = null;
   * let thing2 = null;
   * try {
   *     let thing = ClosableThing.$create();
   *     let thing2 = ClosableThing.$create();
   *     ...
   * } catch ($exceptionFromTry) {
   *      $primaryExc = $exceptionFromTry;
   *      throw $exceptionFromTry;
   * } finally {
   *     $primaryExc = $Exceptions.safeClose(thing2, $primaryExc);
   *     $primaryExc = $Exceptions.safeClose(thing, $primaryExc);
   *     if ($primaryExc != null) {
   *         throw $primaryExc;
   *     }
   * }
   *
   */
  private List<Statement> removeResourceDeclarations(TryStatement tryStatement) {
    MethodDescriptor safeClose =
        MethodDescriptor.createRaw(
            true,
            Visibility.PUBLIC,
            BootstrapType.EXCEPTIONS.getDescriptor(),
            "safeClose",
            Arrays.asList(
                TypeDescriptors.get().javaLangObject, TypeDescriptors.get().javaLangThrowable),
            TypeDescriptors.get().javaLangThrowable,
            null,
            null,
            false);
    List<Statement> outputStatements = new ArrayList<>();

    Variable primaryException =
        new Variable("$primaryExc", TypeDescriptors.get().javaLangThrowable, false, false);
    VariableDeclarationFragment fragment =
        new VariableDeclarationFragment(primaryException, NullLiteral.NULL);
    VariableDeclarationStatement declarePrimaryException =
        new VariableDeclarationStatement(Arrays.asList(fragment));
    outputStatements.add(declarePrimaryException);

    List<Statement> tryBlockBodyStatements = new ArrayList<>();

    List<VariableDeclarationExpression> resourceDeclarations =
        tryStatement.getResourceDeclarations();
    for (VariableDeclarationExpression declaration : resourceDeclarations) {
      VariableDeclarationFragment originalResourceDeclaration = declaration.getFragments().get(0);
      VariableDeclarationFragment declareResourceNull =
          new VariableDeclarationFragment(
              originalResourceDeclaration.getVariable(), NullLiteral.NULL);
      VariableDeclarationExpression nullDeclaration =
          new VariableDeclarationExpression(Arrays.asList(declareResourceNull));
      Statement openResource = new ExpressionStatement(nullDeclaration);
      outputStatements.add(openResource);

      Expression assignResourceInitializer =
          new BinaryExpression(
              originalResourceDeclaration.getVariable().getTypeDescriptor(),
              originalResourceDeclaration.getVariable().getReference(),
              BinaryOperator.ASSIGN,
              originalResourceDeclaration.getInitializer());
      tryBlockBodyStatements.add(new ExpressionStatement(assignResourceInitializer));
    }
    tryBlockBodyStatements.addAll(tryStatement.getBody().getStatements());

    Variable exceptionFromTry =
        new Variable("$exceptionFromTry", TypeDescriptors.get().javaLangThrowable, false, true);

    List<Statement> catchBlockStatments = new ArrayList<>();
    Expression assignPrimaryExceptionToExceptionFromTry =
        new BinaryExpression(
            primaryException.getTypeDescriptor(),
            primaryException.getReference(),
            BinaryOperator.ASSIGN,
            exceptionFromTry.getReference());
    catchBlockStatments.add(new ExpressionStatement(assignPrimaryExceptionToExceptionFromTry));
    catchBlockStatments.add(new ThrowStatement(exceptionFromTry.getReference()));

    List<Statement> finallyBlockStatments = new ArrayList<>();
    for (VariableDeclarationExpression declaration : Lists.reverse(resourceDeclarations)) {
      MethodCall call =
          MethodCall.createRegularMethodCall(
              null,
              safeClose,
              Arrays.asList(
                  declaration.getFragments().get(0).getVariable().getReference(),
                  primaryException.getReference()));
      Expression assignExceptionFromSafeCloseCall =
          new BinaryExpression(
              primaryException.getTypeDescriptor(),
              primaryException.getReference(),
              BinaryOperator.ASSIGN,
              call);
      finallyBlockStatments.add(new ExpressionStatement(assignExceptionFromSafeCloseCall));
    }

    ThrowStatement throwPrimaryException = new ThrowStatement(primaryException.getReference());
    Expression primaryExceptionNotEqualsNull =
        new BinaryExpression(
            TypeDescriptors.get().primitiveBoolean,
            primaryException.getReference(),
            BinaryOperator.NOT_EQUALS,
            NullLiteral.NULL);
    IfStatement primaryExceptionNullStatement =
        new IfStatement(primaryExceptionNotEqualsNull, throwPrimaryException, null);
    finallyBlockStatments.add(primaryExceptionNullStatement);

    CatchClause catchTryException =
        new CatchClause(new Block(catchBlockStatments), exceptionFromTry);
    TryStatement innerTryStatement =
        new TryStatement(
            new ArrayList<VariableDeclarationExpression>(),
            new Block(tryBlockBodyStatements),
            Arrays.asList(catchTryException),
            new Block(finallyBlockStatments));

    outputStatements.add(innerTryStatement);
    return outputStatements;
  }
}
