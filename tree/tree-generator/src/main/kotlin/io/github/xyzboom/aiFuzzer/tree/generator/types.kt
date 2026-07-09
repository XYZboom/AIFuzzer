package io.github.xyzboom.aiFuzzer.tree.generator

import org.jetbrains.kotlin.generators.tree.ClassRef
import org.jetbrains.kotlin.generators.tree.PositionTypeParameterRef
import org.jetbrains.kotlin.generators.tree.TypeKind
import org.jetbrains.kotlin.generators.tree.imports.ArbitraryImportable
import org.jetbrains.kotlin.generators.tree.withArgs

internal const val BASE_PACKAGE = "io.github.xyzboom.aiFuzzer.ir"
internal const val VISITOR_PACKAGE = "$BASE_PACKAGE.visitors"

val pureAbstractElementType = generatedType("UirPureAbstractElement")
val irImplementationDetailType = generatedType("IrImplementationDetail")
val irBuilderDslAnnotation = generatedType("BuilderDsl", packageName = "builder", kind = TypeKind.Class)

val irVisitorType = generatedType("UirVisitor", "visitors", kind = TypeKind.Class)
val irVisitorVoidType = generatedType("UirVisitorVoid", "visitors", kind = TypeKind.Class)
val irDefaultVisitorType = generatedType("UirDefaultVisitor", "visitors", kind = TypeKind.Class)
val irDefaultVisitorVoidType = generatedType("UirDefaultVisitorVoid", "visitors", kind = TypeKind.Class)
val irTransformerType = generatedType("UirTransformer", "visitors", kind = TypeKind.Class)

// 枚举类型定义（手写）
val typeKindType = generatedType("UirTypeKind")
val dimKindType = generatedType("UirDimKind")
val attrKindType = generatedType("UirAttrKind")
val blockKindType = generatedType("UirBlockKind")
val opKindType = generatedType("UirOpKind")

val transformInPlaceImport = ArbitraryImportable(VISITOR_PACKAGE, "transformInplace")

/** 创建一个类型引用，包名自动使用 BASE_PACKAGE 作为前缀 */
fun generatedType(name: String): ClassRef<PositionTypeParameterRef> =
    generatedType(name, "")

fun generatedType(name: String, packageName: String): ClassRef<PositionTypeParameterRef> {
    val realPackage = if (packageName.isNotBlank()) "$BASE_PACKAGE.$packageName" else BASE_PACKAGE
    return org.jetbrains.kotlin.generators.tree.type(realPackage, name, kind = TypeKind.Interface)
}

fun generatedType(name: String, packageName: String, kind: TypeKind): ClassRef<PositionTypeParameterRef> {
    val realPackage = if (packageName.isNotBlank()) "$BASE_PACKAGE.$packageName" else BASE_PACKAGE
    return org.jetbrains.kotlin.generators.tree.type(realPackage, name, kind = kind)
}

/** 创建一个带前缀包名的类型（非 BASE_PACKAGE 下） */
fun type(packageName: String, name: String, kind: TypeKind = TypeKind.Class): ClassRef<PositionTypeParameterRef> =
    org.jetbrains.kotlin.generators.tree.type("$BASE_PACKAGE.$packageName", name, kind = kind)