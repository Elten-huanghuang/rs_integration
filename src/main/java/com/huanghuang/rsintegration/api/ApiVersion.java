package com.huanghuang.rsintegration.api;

public record ApiVersion(int major, int minor, int patch) {
    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("API versions must be non-negative");
        }
    }

    public boolean supports(int requiredMajor, int requiredMinor, int requiredPatch) {
        if (requiredMajor != major) return false;
        if (requiredMinor != minor) return requiredMinor < minor;
        return requiredPatch <= patch;
    }
}
