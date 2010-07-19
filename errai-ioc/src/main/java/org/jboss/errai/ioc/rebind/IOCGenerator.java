/*
 * Copyright 2009 JBoss, a divison Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.ioc.rebind;

import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JParameterizedType;
import com.google.gwt.core.ext.typeinfo.NotFoundException;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import org.jboss.errai.bus.client.ErraiBus;
import org.jboss.errai.bus.client.api.MessageCallback;
import org.jboss.errai.bus.client.framework.MessageBus;
import org.jboss.errai.bus.rebind.ProcessingContext;
import org.jboss.errai.bus.server.ErraiBootstrapFailure;
import org.jboss.errai.bus.server.annotations.Service;
import org.jboss.errai.bus.server.util.RebindUtil;
import org.jboss.errai.bus.server.util.RebindVisitor;
import org.jboss.errai.ioc.client.InterfaceInjectionContext;
import org.jboss.errai.ioc.client.api.*;
import org.jboss.errai.ioc.rebind.ioc.IOCExtensionConfigurator;
import org.jboss.errai.ioc.rebind.ioc.InjectionFailure;
import org.jboss.errai.ioc.rebind.ioc.InjectorFactory;
import org.jboss.errai.ioc.rebind.ioc.ProviderInjector;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;

import static org.jboss.errai.bus.server.util.ConfigUtil.findAllConfigTargets;

/**
 * The main generator class for the errai-ioc framework.
 */
public class IOCGenerator extends Generator {
    /**
     * Simple name of class to be generated
     */
    private String className = null;

    /**
     * Package name of class to be generated
     */
    private String packageName = null;

    private TypeOracle typeOracle;

    private ProcessingContext procContext;
    private InjectorFactory injectFactory;
    private ProcessorFactory procFactory;

    private List<Runnable> deferredTasks = new LinkedList<Runnable>();

    public IOCGenerator() {
    }

    public IOCGenerator(ProcessingContext processingContext) {
        this();
        this.procContext = processingContext;
        this.typeOracle = processingContext.getOracle();
        this.injectFactory = new InjectorFactory(processingContext);
        this.procFactory = new ProcessorFactory(injectFactory);
        defaultConfigureProcessor();
    }

    @Override
    public String generate(TreeLogger logger, GeneratorContext context, String typeName)
            throws UnableToCompleteException {
        typeOracle = context.getTypeOracle();

        try {
            // get classType and save instance variables

            JClassType classType = typeOracle.getType(typeName);
            packageName = classType.getPackage().getName();
            className = classType.getSimpleSourceName() + "Impl";

            logger.log(TreeLogger.INFO, "Generating Extensions Bootstrapper...");

            // Generate class source code
            generateIOCBootstrapClass(logger, context);
        }
        catch (Throwable e) {
            // record sendNowWith logger that Map generation threw an exception
            e.printStackTrace();
            logger.log(TreeLogger.ERROR, "Error generating extensions", e);
        }

        // return the fully qualified name of the class generated
        return packageName + "." + className;
    }

    /**
     * Generate source code for new class. Class extends
     * <code>HashMap</code>.
     *
     * @param logger  Logger object
     * @param context Generator context
     */
    private void generateIOCBootstrapClass(TreeLogger logger, GeneratorContext context) {
        // get print writer that receives the source code
        PrintWriter printWriter = context.tryCreate(logger, packageName, className);
        // print writer if null, source code has ALREADY been generated,

        if (printWriter == null) return;

        // init composer, set class properties, create source writer
        ClassSourceFileComposerFactory composer = new ClassSourceFileComposerFactory(packageName,
                className);

        composer.addImplementedInterface(Bootstrapper.class.getName());
        composer.addImport(InterfaceInjectionContext.class.getName());
        composer.addImport(Widget.class.getName());
        composer.addImport(List.class.getName());
        composer.addImport(ArrayList.class.getName());
        composer.addImport(Map.class.getName());
        composer.addImport(HashMap.class.getName());
        composer.addImport(com.google.gwt.user.client.ui.Panel.class.getName());
        composer.addImport(ErraiBus.class.getName());

        SourceWriter sourceWriter = composer.createSourceWriter(context, printWriter);

        procContext = new ProcessingContext(logger, context, sourceWriter, typeOracle);
        injectFactory = new InjectorFactory(procContext);
        procFactory = new ProcessorFactory(injectFactory);
        defaultConfigureProcessor();

        // generator constructor source code
        initializeProviders(context, logger, sourceWriter);
        generateExtensions(context, logger, sourceWriter);
        // close generated class
        sourceWriter.outdent();
        sourceWriter.println("}");

        // commit generated class
        context.commit(logger, printWriter);
    }

    public void initializeProviders(final GeneratorContext context, final TreeLogger logger, final SourceWriter sourceWriter) {
        final List<File> targets = findAllConfigTargets();

        final JClassType typeProviderCls;

        try {
            typeProviderCls = typeOracle.getType(TypeProvider.class.getName());
        }
        catch (NotFoundException e) {
            throw new RuntimeException(e);
        }

        RebindUtil.visitAllTargets(targets, context, logger, sourceWriter, typeOracle, new RebindVisitor() {
            public void visit(JClassType visit, GeneratorContext context, TreeLogger logger, SourceWriter writer) {
                if (visit.isAnnotationPresent(Provider.class)) {

                    JClassType bindType = null;

                    for (JClassType iface : visit.getImplementedInterfaces()) {
                        if (!typeProviderCls.isAssignableFrom(iface)) {
                            continue;
                        }

                        JParameterizedType pType = iface.isParameterized();

                        if (pType == null) {
                            throw new InjectionFailure("could not determine the bind type for the Provider class: " + visit.getQualifiedSourceName());
                        }

                        bindType = pType.getTypeArgs()[0];
                    }

                    if (bindType == null) {
                        throw new InjectionFailure("the annotated provider class does not appear to implement " + TypeProvider.class.getName() + ": " + visit.getQualifiedSourceName());
                    }

                    final JClassType finalBindType = bindType;

                    injectFactory.addInjector(new ProviderInjector(finalBindType, visit));
                }
                else if (visit.isAnnotationPresent(IOCExtension.class)) {
                     try {
                         Class<? extends IOCExtensionConfigurator> configuratorClass = Class.forName(visit.getQualifiedSourceName())
                                 .asSubclass(IOCExtensionConfigurator.class);

                         configuratorClass.newInstance().configure(procContext, injectFactory, procFactory);
                     }
                     catch (Exception e) {
                         throw new ErraiBootstrapFailure("unable to load IOC Extension Configurator: " + e.getMessage(), e);
                     }
                }
            }

            public void visitError(String className, Throwable t) {
            }
        });
    }

    private void generateExtensions(final GeneratorContext context, final TreeLogger logger, final SourceWriter sourceWriter) {
        // start constructor source generation
        sourceWriter.println("public " + className + "() { ");
        sourceWriter.indent();
        sourceWriter.println("super();");
        sourceWriter.outdent();
        sourceWriter.println("}");

        sourceWriter.println("public InterfaceInjectionContext bootstrapContainer() { ");
        sourceWriter.outdent();
        sourceWriter.println("InterfaceInjectionContext ctx = new InterfaceInjectionContext();");

        RebindUtil.visitAllTargets(findAllConfigTargets(), context, logger, sourceWriter, typeOracle,
                new RebindVisitor() {
                    public void visit(JClassType visitC, GeneratorContext context, TreeLogger logger, SourceWriter writer) {
                        procFactory.process(visitC, procContext);
                    }

                    public void visitError(String className, Throwable t) {
                    }
                }

        );

        runAllDeferred();

        sourceWriter.println("return ctx;");
        sourceWriter.outdent();
        sourceWriter.println("}");
    }

    public void addType(final JClassType type) {
        injectFactory.addType(type);
    }

    public String generateWithSingletonSemantics(final JClassType visit) {
        return injectFactory.generateSingleton(visit);
    }

    public String generateInjectors(final JClassType visit) {
        return injectFactory.generate(visit);
    }

    public String generateAllProviders() {
        return injectFactory.generateAllProviders();
    }

    public void addDeferred(Runnable task) {
        deferredTasks.add(task);
    }

    private void runAllDeferred() {
        for (Runnable r : deferredTasks)
            r.run();
    }

    public JClassType getJClassType(Class cls) {
        try {
            return typeOracle.getType(cls.getName());
        }
        catch (NotFoundException e) {
            return null;
        }
    }

    private void defaultConfigureProcessor() {
        final JClassType widgetType = getJClassType(Widget.class);
        final JClassType messageCallbackType = getJClassType(MessageCallback.class);
        final JClassType messageBusType = getJClassType(MessageBus.class);

        procFactory.registerHandler(EntryPoint.class, new AnnotationHandler<EntryPoint>() {
            public void handle(final JClassType type, EntryPoint annotation, ProcessingContext context) {
                addDeferred(new Runnable() {
                    public void run() {
                        generateWithSingletonSemantics(type);
                    }
                });
            }
        });

        procFactory.registerHandler(ToRootPanel.class, new AnnotationHandler<ToRootPanel>() {
            public void handle(final JClassType type, final ToRootPanel annotation, final ProcessingContext context) {
                if (widgetType.isAssignableFrom(type)) {

                    addDeferred(new Runnable() {
                        public void run() {
                            context.getWriter().println("ctx.addToRootPanel(" + generateWithSingletonSemantics(type) + ");");
                        }
                    });


                } else {
                    throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
                            + "  but does not extend type Widget: " + type.getQualifiedSourceName());
                }
            }
        });

        procFactory.registerHandler(CreatePanel.class, new AnnotationHandler<CreatePanel>() {
            public void handle(final JClassType type, final CreatePanel annotation, final ProcessingContext context) {
                if (widgetType.isAssignableFrom(type)) {

                    addDeferred(new Runnable() {
                        public void run() {
                            context.getWriter().println("ctx.registerPanel(\"" + (annotation.value().equals("")
                                    ? type.getName() : annotation.value()) + "\", " + generateInjectors(type) + ");");
                        }
                    });
                } else {
                    throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
                            + "  but does not extend type Widget: " + type.getQualifiedSourceName());
                }
            }
        });

        procFactory.registerHandler(ToPanel.class, new AnnotationHandler<ToPanel>() {
            public void handle(final JClassType type, final ToPanel annotation, final ProcessingContext context) {
                if (widgetType.isAssignableFrom(type)) {

                    addDeferred(new Runnable() {
                        public void run() {
                            context.getWriter()
                                    .println("ctx.widgetToPanel(" + generateWithSingletonSemantics(type) + ", \"" + annotation.value() + "\");");
                        }
                    });
                } else {
                    throw new InjectionFailure("type declares @" + annotation.getClass().getSimpleName()
                            + "  but does not extend type Widget: " + type.getQualifiedSourceName());
                }
            }
        });

        procFactory.registerHandler(Service.class, new AnnotationHandler<Service>() {
            public void handle(final JClassType type, final Service annotation, final ProcessingContext context) {
                if (messageCallbackType.isAssignableFrom(type)) {
                    addDeferred(new Runnable() {
                        public void run() {
                            String svcName = annotation.value().equals("") ? type.getName() : annotation.value();

                            String busInstance = generateInjectors(messageBusType);
                            String svcInstance = generateWithSingletonSemantics(type);

                            context.getWriter()
                                    .println(busInstance + ".subscribe(\"" + svcName + "\", " + svcInstance + ");");
                        }
                    });
                } else {
                    throw new InjectionFailure("@Service annotated class does not implement MessageCallaback: "
                            + type.getQualifiedSourceName());
                }
            }
        });

    }
}
