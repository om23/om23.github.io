# Anthropic SDK uses Jackson for (de)serialization; keep reflective targets.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Structured-output model classes are populated by Jackson via reflection.
-keep class com.ommahida.inkling.DiaryTurn { *; }

-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
