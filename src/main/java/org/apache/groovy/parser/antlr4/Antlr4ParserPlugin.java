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
package org.apache.groovy.parser.antlr4;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.ParserPlugin;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.io.StringReaderSource;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.codehaus.groovy.syntax.Reduction;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A parser plugin for the new parser.
 */
public class Antlr4ParserPlugin implements ParserPlugin {

    @Override
    public Reduction parseCST(final SourceUnit sourceUnit, final Reader reader) {
        if (!sourceUnit.getSource().canReopenSource()) {
            try {
                sourceUnit.setSource(new StringReaderSource(
                        IOGroovyMethods.getText(reader),
                        sourceUnit.getConfiguration()
                ));
            } catch (IOException e) {
                throw new GroovyBugError("Failed to create StringReaderSource", e);
            }
        }
        return null;
    }


    @Override
    public ModuleNode buildAST(final SourceUnit sourceUnit, final ClassLoader classLoader, final Reduction cst) {
        List<Callable<ModuleNode>> taskList = new ArrayList<>(2);
        SourceUnit su = new SourceUnit(sourceUnit.getName(), sourceUnit.getSource(), sourceUnit.getConfiguration(),
                sourceUnit.getClassLoader(), new ErrorCollector(sourceUnit.getConfiguration()));
        taskList.add(createBuildAstTask(su, AstBuilder.SLL));
        taskList.add(createBuildAstTask(sourceUnit, AstBuilder.ALL));

        ExecutorService threadPool = Executors.newFixedThreadPool(taskList.size(), r -> {
            Thread t = new Thread(r);
            t.setName("parser-thread-" + SEQ.getAndIncrement());
            return t;
        });

        try {
            ModuleNode moduleNode = threadPool.invokeAny(taskList, 10, TimeUnit.SECONDS);
            moduleNode.setContext(sourceUnit);
            return moduleNode;
        } catch (InterruptedException e) {
            throw new GroovyBugError("Interrupted while parsing", e);
        } catch (TimeoutException e) {
            throw new GroovyBugError("Timeout while parsing", e);
        } catch (ExecutionException e) {
            throw (CompilationFailedException) e.getCause();
        } finally {
            threadPool.shutdown();
        }
    }

    private Callable<ModuleNode> createBuildAstTask(final SourceUnit sourceUnit, final int predictionMode) {
        return () -> {
            AstBuilder builder = new AstBuilder(sourceUnit,
                    sourceUnit.getConfiguration().isGroovydocEnabled(),
                    sourceUnit.getConfiguration().isRuntimeGroovydocEnabled(),
                    predictionMode
            );
            return builder.buildAST();
        };
    }

    private static final AtomicLong SEQ = new AtomicLong(0);
}
