package top.nkbe.npatch.repo

import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

/**
 * GitHub Releases API からの Knot リリース情報。
 * VendettaManager のようなリリース追従機能用。
 */
data class KnotRelease(
    @field:SerializedName("tag_name")
    @field:Expose
    var tagName: String? = null,

    @field:SerializedName("name")
    @field:Expose
    var name: String? = null,

    @field:SerializedName("published_at")
    @field:Expose
    var publishedAt: String? = null,

    @field:SerializedName("body")
    @field:Expose
    var body: String? = null,

    @field:SerializedName("prerelease")
    @field:Expose
    var isPrerelease: Boolean = false,

    @field:SerializedName("assets")
    @field:Expose
    var assets: List<KnotReleaseAsset> = emptyList(),
) {
    /** タグ名からバージョン文字列を抽出 (例: "v2.6.0" → "2.6.0") */
    val version: String?
        get() = tagName?.removePrefix("v")?.removePrefix("V")

    /** リリースページ URL */
    val htmlUrl: String?
        get() = tagName?.let { "https://github.com/2b-zipper/Knot/releases/tag/$it" }
}

data class KnotReleaseAsset(
    @field:SerializedName("name")
    @field:Expose
    var name: String? = null,

    @field:SerializedName("browser_download_url")
    @field:Expose
    var browserDownloadUrl: String? = null,

    @field:SerializedName("size")
    @field:Expose
    var size: Long = 0,

    @field:SerializedName("download_count")
    @field:Expose
    var downloadCount: Int = 0,
)
