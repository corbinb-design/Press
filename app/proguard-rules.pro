# kotlinx.serialization ships its own consumer rules; keep our models' serializers explicitly too.
-keep,includedescriptorclasses class app.forge.data.**$$serializer { *; }
-keepclassmembers class app.forge.data.** { *** Companion; }
