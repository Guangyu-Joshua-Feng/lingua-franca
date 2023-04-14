package org.lflang.generator.c;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.EObject;

import org.lflang.generator.CodeBuilder;
import org.lflang.lf.Parameter;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.StateVar;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.Type;
import org.lflang.lf.TypedVariable;
import org.lflang.lf.VarRef;
import org.lflang.lf.Variable;
import org.lflang.util.FileUtil;

public class CReactorHeaderFileGenerator {

    public interface GenerateAuxiliaryStructs {
        void generate(CodeBuilder b, TypeParameterizedReactor r, boolean userFacing);
    }

    public static Path outputPath(CFileConfig fileConfig, TypeParameterizedReactor tpr) {
        return Path.of(Path.of(tpr.r().eResource().getURI().toFileString())
                .getFileName().toString().replaceFirst("[.][^.]+$", ""))
            .resolve(tpr.getName() + ".h");
    }

    public static void doGenerate(CTypes types, TypeParameterizedReactor tpr, CFileConfig fileConfig, GenerateAuxiliaryStructs generator, Function<EObject, String> topLevelPreamble) throws IOException {
        String contents = generateHeaderFile(types, tpr, generator, topLevelPreamble.apply(tpr.r()));
        FileUtil.writeToFile(contents, fileConfig.getIncludePath().resolve(outputPath(fileConfig, tpr)));
    }
    private static String generateHeaderFile(CTypes types, TypeParameterizedReactor tpr, GenerateAuxiliaryStructs generator, String topLevelPreamble) {
        CodeBuilder builder = new CodeBuilder();
        appendIncludeGuard(builder, tpr);
        builder.pr(topLevelPreamble);
        appendPoundIncludes(builder);
        appendSelfStruct(builder, types, tpr);
        generator.generate(builder, tpr, true);
        for (Reaction reaction : tpr.r().getReactions()) {
            appendSignature(builder, types, reaction, tpr);
        }
        builder.pr("#endif");
        return builder.getCode();
    }

    private static void appendIncludeGuard(CodeBuilder builder, TypeParameterizedReactor r) {
        String macro = CUtil.getName(r) + "_H";
        builder.pr("#ifndef " + macro);
        builder.pr("#define " + macro);
    }
    private static void appendPoundIncludes(CodeBuilder builder) {
        builder.pr("""
            #ifdef __cplusplus
            extern "C" {
            #endif
            #include "../include/api/api.h"
            #include "../include/api/set.h"
            #include "../include/api/generics.h"
            #include "../include/utils/type_converter.h"
            #include "../include/core/reactor.h"
            #ifdef __cplusplus
            }
            #endif
            """);
    }

    private static String userFacingSelfType(TypeParameterizedReactor tpr) {
        return tpr.getName().toLowerCase() + "_self_t";
    }

    private static void appendSelfStruct(CodeBuilder builder, CTypes types, TypeParameterizedReactor tpr) {
        builder.pr("typedef struct " + userFacingSelfType(tpr) + "{");
        for (Parameter p : tpr.r().getParameters()) {
            builder.pr(types.getTargetType(p) + " " + p.getName() + ";");
        }
        for (StateVar s : tpr.r().getStateVars()) {
            builder.pr(types.getTargetType(s) + " " + s.getName() + ";");
        }
        builder.pr("int end[0]; // placeholder; MSVC does not compile empty structs");
        builder.pr("} " + userFacingSelfType(tpr) + ";");
    }

    private static void appendSignature(CodeBuilder builder, CTypes types, Reaction r, TypeParameterizedReactor tpr) {
        if (r.getName() != null) builder.pr("void " + r.getName() + "(" + reactionParameters(types, r, tpr) + ");");
    }

    private static String reactionParameters(CTypes types, Reaction r, TypeParameterizedReactor tpr) {
        return Stream.concat(Stream.of(userFacingSelfType(tpr) + "* self"), ioTypedVariableStream(r)
            .map(tv -> tpr.getName().toLowerCase() + "_" + tv.getName() + "_t* " + tv.getName()))
            .collect(Collectors.joining(", "));
    }

    public static String reactionArguments(CTypes types, Reaction r, TypeParameterizedReactor tpr) {
        return Stream.concat(Stream.of(getApiSelfStruct(tpr)), ioTypedVariableStream(r)
                .map(it -> String.format("((%s*) %s)", CGenerator.variableStructType(it, tpr, true), it.getName())))
            .collect(Collectors.joining(", "));
    }

    private static String getApiSelfStruct(TypeParameterizedReactor tpr) {
        return "(" + userFacingSelfType(tpr) + "*) (((char*) self) + sizeof(self_base_t))";
    }

    private static Stream<TypedVariable> ioTypedVariableStream(Reaction r) {
        return varRefStream(r).map(it -> it.getVariable() instanceof TypedVariable tv ? tv : null)
            .filter(Objects::nonNull);
    }

    private static Stream<VarRef> inputVarRefStream(Reaction reaction) {
        return varRefStream(Stream.concat(reaction.getTriggers().stream(), reaction.getSources().stream()));
    }

    private static Stream<VarRef> triggerVarRefStream(Reaction reaction) {
        return varRefStream(reaction.getTriggers().stream());
    }

    private static Stream<VarRef> varRefStream(Stream<TriggerRef> toFilter) {
        return toFilter.map(it -> it instanceof VarRef v ? v : null)
            .filter(Objects::nonNull);
    }

    private static Stream<VarRef> outputVarRefStream(Reaction reaction) {
        return reaction.getEffects().stream();
    }

    private static Stream<Variable> inputVariableStream(Reaction reaction) {
        return inputVarRefStream(reaction).map(VarRef::getVariable);
    }

    private static String representVarRef(VarRef it, String multiportIndex, String bankIndex) {
        String containerRepresentation = it.getContainer() == null ? null :
            it.getContainer().getName() + (
                it.getContainer().getWidthSpec() == null ? "" : "[" + bankIndex + "]"
            );
        String varRepresentation = it.getVariable().getName() + (
            !(it instanceof Port p) ? "" : p.getWidthSpec() == null ? "" : "[" + multiportIndex + "]"
        );
        return (containerRepresentation == null ? "" : containerRepresentation + ".") + varRepresentation;
    }

    private static String cVariableOfVarRef(VarRef it) {
        return it.getContainer() != null ? it.getContainer().getName() : it.getVariable().getName();
    }

    private static Stream<VarRef> varRefStream(Reaction reaction) {
        return Stream.concat(inputVarRefStream(reaction), outputVarRefStream(reaction));
    }
}
