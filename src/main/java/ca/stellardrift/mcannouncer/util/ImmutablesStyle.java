package ca.stellardrift.mcannouncer.util;

import org.immutables.value.Value;

@Value.Style(
    with = "*",
    // of = "new",
    typeImmutable = "*Impl",
    typeImmutableEnclosing = "*Impl",
    typeImmutableNested = "*Impl",
    overshadowImplementation = true,
    visibility = Value.Style.ImplementationVisibility.PACKAGE,
    builderVisibility = Value.Style.BuilderVisibility.PACKAGE,
    set = "*",
    depluralize = true,
    deferCollectionAllocation = true,
    jdkOnly = true,
    optionalAcceptNullable = true,
    nullableAnnotation = "org.checkerframework.checker.nullness.qual.Nullable",
    deepImmutablesDetection = true
)
public @interface ImmutablesStyle {

}
