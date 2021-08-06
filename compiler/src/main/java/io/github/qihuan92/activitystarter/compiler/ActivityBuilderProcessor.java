package io.github.qihuan92.activitystarter.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.sun.tools.javac.code.Symbol;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import io.github.qihuan92.activitystarter.annotation.Builder;
import io.github.qihuan92.activitystarter.annotation.Extra;
import io.github.qihuan92.activitystarter.compiler.entity.ActivityClass;
import io.github.qihuan92.activitystarter.compiler.entity.Field;
import io.github.qihuan92.activitystarter.compiler.utils.AptContext;
import io.github.qihuan92.activitystarter.compiler.utils.PrebuiltTypes;
import io.github.qihuan92.activitystarter.compiler.utils.StringUtils;
import io.github.qihuan92.activitystarter.compiler.utils.TypeUtils;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ActivityBuilderProcessor extends AbstractProcessor {

    private final Map<Element, ActivityClass> activityClasses = new HashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        AptContext.getInstance().init(processingEnv);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add(Builder.class.getCanonicalName());
        types.add(Extra.class.getCanonicalName());
        return types;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return true;
        }
        parseClass(roundEnv);
        parseFields(roundEnv);
        buildFiles();
        return true;
    }

    private void parseClass(RoundEnvironment roundEnv) {
        roundEnv.getElementsAnnotatedWith(Builder.class)
                .stream()
                .filter(element -> element.getKind().isClass())
                .forEach(element -> {
                    if (TypeUtils.isSubType(element.asType(), "android.app.Activity")) {
                        activityClasses.put(element, new ActivityClass((TypeElement) element));
                    }
                });
    }

    private void parseFields(RoundEnvironment roundEnv) {
        roundEnv.getElementsAnnotatedWith(Extra.class)
                .stream()
                .filter(element -> element.getKind().isField())
                .forEach(element -> {
                    ActivityClass activityClass = activityClasses.get(element.getEnclosingElement());
                    if (activityClass != null) {
                        activityClass.addFiled(new Field((Symbol.VarSymbol) element));
                    }
                });
    }

    private void buildFiles() {
        activityClasses.forEach((element, activityClass) -> buildFile(activityClass));
    }

    private void buildFile(ActivityClass activityClass) {
        if (activityClass.isAbstract()) {
            return;
        }
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(activityClass.getBuilderClassName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        buildClass(activityClass, typeBuilder);
        writeJavaToFile(activityClass, typeBuilder.build());
    }

    private void buildClass(ActivityClass activityClass, TypeSpec.Builder builder) {
        buildConstant(activityClass, builder);
        buildField(activityClass, builder);
        buildIntentMethod(activityClass, builder);
        buildInjectMethod(activityClass, builder);
        buildSaveStateMethod(activityClass, builder);
        buildNewIntentMethod(activityClass, builder);
        buildStartMethod(builder);
    }

    private void buildConstant(ActivityClass activityClass, TypeSpec.Builder builder) {
        Set<Field> fields = activityClass.getFields();
        for (Field field : fields) {
            builder.addField(
                    FieldSpec.builder(String.class, field.getConstFieldName(), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$S", field.getName())
                            .build()
            );
        }
    }

    private void buildField(ActivityClass activityClass, TypeSpec.Builder builder) {
        ClassName builderClassTypeName = ClassName.get(activityClass.getPackageName(), activityClass.getBuilderClassName());

        // 创建 builder() 方法
        MethodSpec.Builder builderMethodBuilder = MethodSpec.methodBuilder("builder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(builderClassTypeName)
                .addStatement("$T builder = new $T()", builderClassTypeName, builderClassTypeName);

        Set<Field> fields = activityClass.getFields();
        for (Field field : fields) {
            // 变量
            builder.addField(
                    FieldSpec.builder(field.asTypeName(), field.getName(), Modifier.PRIVATE).build()
            );

            if (field.isRequired()) {
                // 添加到 builder() 参数
                builderMethodBuilder.addParameter(
                        ParameterSpec.builder(field.asTypeName(), field.getName())
                                .build()
                );

                // 变量赋值
                builderMethodBuilder.addStatement("builder.$L = $L", field.getName(), field.getName());
            } else {
                // setter
                builder.addMethod(
                        MethodSpec.methodBuilder(field.getName())
                                .addModifiers(Modifier.PUBLIC)
                                .addParameter(field.asTypeName(), field.getName())
                                .addStatement("this.$L = $L", field.getName(), field.getName())
                                .addStatement("return this")
                                .returns(builderClassTypeName)
                                .build()
                );
            }

        }

        builder.addMethod(builderMethodBuilder.addStatement("return builder").build());
    }

    private void buildIntentMethod(ActivityClass activityClass, TypeSpec.Builder builder) {
        MethodSpec.Builder intentMethodBuilder = MethodSpec.methodBuilder("getIntent")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(PrebuiltTypes.CONTEXT.java(), "context")
                .returns(PrebuiltTypes.INTENT.java())
                .addStatement("$T intent = new $T(context, $T.class)", PrebuiltTypes.INTENT.java(), PrebuiltTypes.INTENT.java(), activityClass.getTypeElement());
        Set<Field> fields = activityClass.getFields();
        for (Field field : fields) {
            intentMethodBuilder.addStatement("intent.putExtra($L, $L)", field.getConstFieldName(), field.getName());
        }
        intentMethodBuilder.addStatement("return intent");
        builder.addMethod(intentMethodBuilder.build());
    }

    private void buildInjectMethod(ActivityClass activityClass, TypeSpec.Builder builder) {
        MethodSpec.Builder injectMethodBuilder = MethodSpec.methodBuilder("inject")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(PrebuiltTypes.ACTIVITY.java(), "instance")
                .addParameter(PrebuiltTypes.BUNDLE.java(), "savedInstanceState")
                .beginControlFlow("if(instance instanceof $T)", activityClass.getTypeElement())
                .addStatement("$T typedInstance = ($T) instance", activityClass.getTypeElement(), activityClass.getTypeElement())
                .beginControlFlow("if(savedInstanceState != null)");

        Set<Field> fields = activityClass.getFields();
        for (Field field : fields) {
            String name = field.getName();
            TypeName typeName = field.asTypeName().box();

            if (field.isPrivate()) {
                injectMethodBuilder.addStatement("typedInstance.set$L($T.<$T>get(savedInstanceState, $S, $L))",
                        StringUtils.capitalize(name),
                        PrebuiltTypes.BUNDLE_UTILS.java(),
                        typeName,
                        name,
                        field.getDefaultValue());
            } else {
                injectMethodBuilder.addStatement("typedInstance.$L = $T.<$T>get(savedInstanceState, $S, $L)",
                        name,
                        PrebuiltTypes.BUNDLE_UTILS.java(),
                        typeName,
                        name,
                        field.getDefaultValue());
            }
        }

        injectMethodBuilder.endControlFlow().endControlFlow();
        builder.addMethod(injectMethodBuilder.build());
    }

    private void buildSaveStateMethod(ActivityClass activityClass, TypeSpec.Builder builder) {
        MethodSpec.Builder saveStateMethodBuilder = MethodSpec.methodBuilder("saveState")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(PrebuiltTypes.ACTIVITY.java(), "instance")
                .addParameter(PrebuiltTypes.BUNDLE.java(), "outState")
                .beginControlFlow("if(instance instanceof $T)", activityClass.getTypeElement())
                .addStatement("$T typedInstance = ($T) instance", activityClass.getTypeElement(), activityClass.getTypeElement())
                .addStatement("$T intent = new $T()", PrebuiltTypes.INTENT.java(), PrebuiltTypes.INTENT.java());

        Set<Field> fields = activityClass.getFields();
        for (Field field : fields) {
            String name = field.getName();
            if (field.isPrivate()) {
                saveStateMethodBuilder.addStatement("intent.putExtra($S, typedInstance.get$L())", name, StringUtils.capitalize(name));
            } else {
                saveStateMethodBuilder.addStatement("intent.putExtra($S, typedInstance.$L)", name, name);
            }
        }

        saveStateMethodBuilder.addStatement("outState.putAll(intent.getExtras())").endControlFlow();
        builder.addMethod(saveStateMethodBuilder.build());
    }

    private void buildNewIntentMethod(ActivityClass activityClass, TypeSpec.Builder builder) {
        MethodSpec.Builder newIntentMethodBuilder = MethodSpec.methodBuilder("processNewIntent")
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(TypeName.get(activityClass.getTypeElement().asType()), "activity")
                .addParameter(PrebuiltTypes.INTENT.java(), "intent");
        newIntentMethodBuilder.addStatement("processNewIntent(activity, intent, true)");

        builder.addMethod(newIntentMethodBuilder.build());

        MethodSpec.Builder newIntentWithUpdateMethodBuilder = MethodSpec.methodBuilder("processNewIntent")
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(TypeName.get(activityClass.getTypeElement().asType()), "activity")
                .addParameter(PrebuiltTypes.INTENT.java(), "intent")
                .addParameter(Boolean.class, "updateIntent");

        newIntentWithUpdateMethodBuilder.beginControlFlow("if(updateIntent)")
                .addStatement("activity.setIntent(intent)")
                .endControlFlow();

        newIntentWithUpdateMethodBuilder.beginControlFlow("if(intent != null)")
                .addStatement("inject(activity, intent.getExtras())")
                .endControlFlow();

        builder.addMethod(newIntentWithUpdateMethodBuilder.build());
    }

    private void buildStartMethod(TypeSpec.Builder builder) {
        builder.addMethod(startMethodBuilder(false).build());
        builder.addMethod(startMethodBuilder(true).build());
        builder.addMethod(startForResultMethodBuilder(false).build());
        builder.addMethod(startForResultMethodBuilder(true).build());
    }

    private MethodSpec.Builder startMethodBuilder(boolean withOptions) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("start")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(PrebuiltTypes.CONTEXT.java(), "context")
                .addStatement("$T intent = getIntent(context)", PrebuiltTypes.INTENT.java());

        builder.beginControlFlow("if(!(context instanceof Activity))");
        builder.addStatement("intent.addFlags($T.FLAG_ACTIVITY_NEW_TASK)", PrebuiltTypes.INTENT.java());
        builder.endControlFlow();

        if (withOptions) {
            builder.addParameter(PrebuiltTypes.BUNDLE.java(), "options");
            builder.addStatement("context.startActivity(intent, options)");
        } else {
            builder.addStatement("context.startActivity(intent)");
        }
        return builder;
    }

    private MethodSpec.Builder startForResultMethodBuilder(boolean withOptions) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("startForResult")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(PrebuiltTypes.ACTIVITY.java(), "activity")
                .addParameter(TypeName.INT, "requestCode")
                .addStatement("$T intent = getIntent(activity)", PrebuiltTypes.INTENT.java());

        if (withOptions) {
            builder.addParameter(PrebuiltTypes.BUNDLE.java(), "options");
            builder.addStatement("activity.startActivityForResult(intent, requestCode, options)");
        } else {
            builder.addStatement("activity.startActivityForResult(intent, requestCode)");
        }

        return builder;
    }

    private void writeJavaToFile(ActivityClass activityClass, TypeSpec typeSpec) {
        try {
            JavaFile file = JavaFile.builder(activityClass.getPackageName(), typeSpec).build();
            file.writeTo(AptContext.getInstance().getFiler());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}