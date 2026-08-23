package top.nkbe.npatch.util

const val LINE_PACKAGE_NAME = "jp.naver.line.android"

private const val LINE_VERSION_CODE_MIN_LENGTH = 8

fun formatLineVersionName(versionCode: Long): String {
    val str = versionCode.toString()
    if (str.length < LINE_VERSION_CODE_MIN_LENGTH) return str
    val major = str.substring(0, 2).toIntOrNull() ?: return str
    val minor = str.substring(2, 4).toIntOrNull() ?: return str
    val patch = str.substring(4, 5).toIntOrNull() ?: return str
    return "$major.$minor.$patch"
}
