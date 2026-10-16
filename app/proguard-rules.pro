# Keep ViewBinding classes
-keep class com.mithun.simcontacts.databinding.** { *; }

# Keep data classes used with ContentResolver
-keepclassmembers class com.mithun.simcontacts.PhoneContact { *; }
-keepclassmembers class com.mithun.simcontacts.SimManager$SimCard { *; }
-keepclassmembers class com.mithun.simcontacts.SimManager$SimInfo { *; }
-keepclassmembers class com.mithun.simcontacts.SimManager$SimEntry { *; }
