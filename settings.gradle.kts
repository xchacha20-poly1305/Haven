pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack hosts mik3y/usb-serial-for-android (not on Maven Central).
        // Scoped to that group so the resolver doesn't fall back to JitPack
        // for any other dependency.
        maven {
            url = uri("https://jitpack.io")
            content {
                // mik3y/usb-serial-for-android — the USB-serial terminal driver
                // (CDC-ACM + CH34x/FTDI/CP21xx). JitPack-only, not on Central.
                includeGroup("com.github.mik3y")
            }
        }
    }
}

// Build termlib from source (submodule fork with popScrollbackLine fix).
// Drop this includeBuild once the fix is merged upstream and released.
includeBuild("termlib") {
    dependencySubstitution {
        substitute(module("org.connectbot:termlib")).using(project(":lib"))
    }
}

// Pure Kotlin ET transport library (submodule).
includeBuild("et-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:et-transport")).using(project(":"))
    }
}

// Pure Kotlin SSP transport library (submodule).
includeBuild("mosh-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:ssp-transport")).using(project(":"))
    }
}

// IronRDP + UniFFI Kotlin bindings (submodule).
includeBuild("rdp-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:rdp-transport")).using(project(":"))
    }
}

// Pure-Rust SPICE client + UniFFI Kotlin bindings (#286, submodule GlassOnTin/spice-kotlin).
includeBuild("spice-kotlin") {
    dependencySubstitution {
        substitute(module("sh.haven:spice-transport")).using(project(":"))
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:terminal-haven")
include(":core:toolbar")
include(":core:ssh")
include(":core:security")
include(":core:data")
include(":core:redact")
include(":core:tunnel")
include(":core:knock")
include(":core:spa")
include(":core:stepca")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:keys")
include(":feature:tunnel")
include(":core:mosh")
include(":core:et")
include(":core:btserial")
include(":core:usbserial")
include(":core:bleserial")
include(":core:vnc")
include(":core:rdp")
include(":core:spice")
include(":core:glkvm")
include(":core:smb")
include(":core:fido")
include(":core:usb")

include(":feature:settings")
include(":feature:editor")
include(":feature:vnc")
include(":feature:rdp")

include(":integration-tests")

include(":core:scan")
