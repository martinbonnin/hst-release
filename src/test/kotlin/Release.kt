import com.damnhandy.uri.template.UriTemplate
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Test
import org.w3c.dom.Node.ELEMENT_NODE
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import okhttp3.*
import okio.Okio
import java.util.*
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*
import com.sun.xml.internal.ws.addressing.EndpointReferenceUtil.transform
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter
import javax.xml.transform.dom.DOMSource


class Release {

    @JsonClass(generateAdapter = true)
    class Config(
        val hstracker_dir: String,
        val hsdecktracker_net_dir: String,
        val github_token: String,
        val hockey_app_token: String,
        val sparkle_dir: String,
        val apple_password: String
    )

    val config = readConfig()
    val hockeyAppAppId = "2f0021b9bb1842829aa1cfbbd85d3bed"

    val cal = GregorianCalendar()
    val releaseDir =
        "${config.hstracker_dir}/archive/${cal.get(Calendar.YEAR)}_${cal.get(Calendar.MONTH) + 1}_${cal.get(Calendar.DAY_OF_MONTH)}"
    val optionsPlistPath = "$releaseDir/options.plist"
    val hstrackerPath = "$releaseDir/HSTracker"
    val hstrackerAppPath = "$releaseDir/HSTracker.app"
    val hstrackerXcarchivePath = "$releaseDir/HSTracker.xcarchive"
    val hstrackerXcarchiveDSYMPath = "$releaseDir/HSTracker.xcarchive/dSYMs"
    val hstrackerAppZipPath = "$releaseDir/HSTracker.app.zip"
    val hstrackerDSYMZipPath = "$releaseDir/HSTracker.dSYMs.zip"

    val infoPlistPath = "${config.hstracker_dir}/HSTracker/Info.plist"
    val projectPath = "${config.hstracker_dir}/HSTracker.xcodeproj/project.pbxproj"
    val changelogMdPath = "${config.hstracker_dir}/CHANGELOG.md"

    val generateAppcast = "${config.sparkle_dir}/bin/generate_appcast"
    val appCast2ReleaseXmlPath = "$releaseDir/appcast2.xml"

    val appCast2XmlPath = "${config.hsdecktracker_net_dir}/hstracker/appcast2.xml"

    val dryRun = false
    val moshi = Moshi.Builder().build()
    val mapAdapter = moshi.adapter<Map<String, Any>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )
    )

    val okHttpClient by lazy {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return arrayOf()
            }
        })

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        val builder = OkHttpClient.Builder()
        builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }

        builder.build()
    }
    val pListContents = """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>method</key>
                    <string>developer-id</string>
                </dict>
                </plist>""".trimIndent()

    private fun readConfig(): Config {
        return try {
            val source = Okio.buffer(Okio.source(File("./config.json")))
            Moshi.Builder().build().adapter(Config::class.java).fromJson(source)!!
        } catch (e: Exception) {
            throw Exception("define a valid config.json", e)
        }
    }

    private fun getPlistVersion(): String {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(File(infoPlistPath))

        val plist = document.documentElement;
        val dict = plist.getElementsByTagName("dict").item(0)
        val nodes = dict.childNodes

        val nodeArray = Array(nodes.length) { nodes.item(it) }

        nodeArray.first().textContent

        val keys = nodeArray.filter { it.nodeType == ELEMENT_NODE && it.nodeName == "key" }
        val values = nodeArray.filter { it.nodeType == ELEMENT_NODE && it.nodeName == "string" }

        val plistVersion = values[keys.indexOfFirst { it.textContent == "CFBundleShortVersionString" }].textContent

        if (plistVersion == "\$(MARKETING_VERSION)") {
            val regex = Regex("\\s*MARKETING_VERSION *= *(.*);")
            val matchResult = File(projectPath).readLines().mapNotNull {
                regex.matchEntire(it)
            }.firstOrNull()

            if (matchResult == null) {
                throw Exception("cannot find MARKETING_VERSION")
            }

            return matchResult.groupValues[1]
        } else {
            return plistVersion
        }
    }

    data class ChangelogEntry(val version: String, val markdown: String)

    private fun toHtml(md: String): String {
        val parser = Parser.builder().build()
        val renderer = HtmlRenderer.builder().build()

        val parsedDocument = parser.parse(md)
        return renderer.render(parsedDocument)
    }

    private fun getChangelog(): List<ChangelogEntry> {
        val lines = File(changelogMdPath).readLines()

        var markdownLines = mutableListOf<String>()
        var currentVersion: String? = null

        val list = mutableListOf<ChangelogEntry>()
        val regex = Regex("# (.*)")
        lines.forEach {
            val matchResult = regex.matchEntire(it)
            if (matchResult != null) {
                if (currentVersion != null) {
                    list.add(ChangelogEntry(currentVersion!!, markdownLines.joinToString("\n")))
                }
                currentVersion = matchResult.groupValues[1]
                markdownLines.clear()
            } else {
                markdownLines.add(it)
            }
        }
        if (currentVersion != null) {
            list.add(ChangelogEntry(currentVersion!!, markdownLines.joinToString("\n")))
        }

        return list
    }

    @Test
    fun build() {
        // codesign --force -o runtime -s 'Developer ID Application: HearthSim, LLC (RL7C49LAMC)' Carthage/Build/Mac/Sparkle.framework/Resources/Autoupdate.app/Contents/MacOS/fileop
        // codesign --force -o runtime -s 'Developer ID Application: HearthSim, LLC (RL7C49LAMC)' Carthage/Build/Mac/Sparkle.framework/Resources/Autoupdate.app/Contents/MacOS/Autoupdate
        File(releaseDir).mkdirs()
        File(optionsPlistPath).writeText(pListContents)
        runCommand(config.hstracker_dir, "xcodebuild -scheme HSTracker clean")
        runCommand(config.hstracker_dir, "xcodebuild -archivePath $hstrackerPath -scheme HSTracker archive")
        runCommand(
            config.hstracker_dir,
            "xcodebuild -exportArchive -archivePath $hstrackerXcarchivePath -exportPath $releaseDir -exportOptionsPlist $optionsPlistPath"
        )

        zip(hstrackerAppPath, hstrackerAppZipPath)
        zip(hstrackerXcarchiveDSYMPath, hstrackerDSYMZipPath)
    }

    @Test
    fun sendForNotarization() {
        val password = config.apple_password
        val command = "xcrun altool --notarize-app --verbose --primary-bundle-id \"net.hearthsim.hstracker\" --username 'martin@mbonnin.net' --password '$password' --file $hstrackerAppZipPath -itc_provider RL7C49LAMC"

        println("command: $command")

        /*val result = getCommandOutput(
            config.hstracker_dir,
            command
        )

        result.lines().forEach {
            val regex = Regex("RequestUUID = (.*)")
            val match = regex.matchEntire(it)
            if (match != null) {
                System.out.println("Notarization Request: ${match.groupValues[1]}")
            }
        }*/
    }

    @Test
    fun checkNotarization() {
        val password = config.apple_password
        val requestId = ""

        val result = getCommandOutput(
            config.hstracker_dir,
            "xcrun altool --notarization-info $requestId -u martin@mbonnin.net --password '$password'"
        )

        result.lines().forEach {
            val regex = Regex("RequestUUID = (.*)")
            val match = regex.matchEntire(it)
            if (match != null) {
                System.out.println("Notarization Request: ${match.groupValues[1]}")
            }
        }
    }

    @Test
    fun doRelease() {
        System.out.println("Releasing HSTracker")

        val plistVersion = getPlistVersion()
        val changelog = getChangelog()

        if (changelog.isEmpty()) {
            throw Exception("Changelog is empty :-/")
        }

        val changelogVersion = changelog.first().version

        System.out.println("changelogVersion=$changelogVersion")
        System.out.println("plistVersion=$plistVersion")

        if (changelogVersion != plistVersion) {
            throw Exception("versions do not match, either update the CHANGELOG.md or Info.plist")
        }

        //build()

        runCommand(config.hstracker_dir, "git tag $plistVersion")
        runCommand(config.hstracker_dir, "git push origin --tags")
        runCommand(config.hstracker_dir, "git push origin master")

        uploadToHockeyApp(changelog.first().markdown)
        makeGithubRelease(changelogVersion, changelog.first().markdown)

        // not sure why we need to remove the sparkle cache but we do else it reuses previous versions
        runCommand(config.hstracker_dir, "rm -rf /Users/Martin/Library/Caches/Sparkle_generate_appcast/")
        // generateAppCast will output some warnings, that's ok at this point
        // Warning: Private key not found in the Keychain (-25300). Please run the generate_keys tool
        // Could not unarchive /Users/martin/git/HSTracker/archive/2019_6_6/options.plist Error Domain=SUSparkleErrorDomain Code=3000 "Not a supported archive format: file:///Users/martin/Library/Caches/Sparkle_generate_appcast/options.plist.tmp/options.plist" UserInfo={NSLocalizedDescription=Not a supported archive format: file:///Users/martin/Library/Caches/Sparkle_generate_appcast/options.plist.tmp/options.plist}
        runCommand(config.hstracker_dir, "$generateAppcast ${config.hstracker_dir}/dsa_priv.pem $releaseDir")

        runCommand(config.hsdecktracker_net_dir, "git checkout master")
        runCommand(config.hsdecktracker_net_dir, "git pull")
        runCommand(config.hsdecktracker_net_dir, "git stash")
        updateAppCast(plistVersion, changelog.first().markdown)
    }

    private fun updateAppCast(versionName: String, markdown: String) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val releaseAppcastDocument = builder.parse(File(appCast2ReleaseXmlPath))

        val releaseItems = releaseAppcastDocument.documentElement.getElementsByTagName("item")
        if (releaseItems.length != 1) {
            throw Exception("Appcast has generated too many items ${releaseItems.length}")
        }
        val itemElement = (releaseItems.item(0) as Element)
        val enclosureNode = itemElement.getElementsByTagName("enclosure").item(0)
        (enclosureNode as Element).setAttribute(
            "url",
            "https://github.com/HearthSim/HSTracker/releases/download/$versionName/HSTracker.app.zip"
        )

        val appcastDocument = builder.parse(File(appCast2XmlPath))

        val importedNode = appcastDocument.importNode(itemElement, true)
        val pubDate = (importedNode as Element).getElementsByTagName("pubDate").item(0)
        val description = appcastDocument.createElement("description")
        description.textContent = toHtml(markdown)
        importedNode.insertBefore(description, pubDate.nextSibling)
        val channelNode = appcastDocument.documentElement.getElementsByTagName("channel").item(0)
        channelNode.insertBefore(importedNode, channelNode.firstChild)

        val result = StreamResult(File(appCast2XmlPath))
        val tf = TransformerFactory.newInstance()
        val transformer = tf.newTransformer()
        transformer.transform(DOMSource(appcastDocument), result)
    }

    private fun uploadToHockeyApp(markdown: String) {
        System.out.println("uploading $hstrackerAppZipPath to hockeyapp")
        System.out.println("uploading $hstrackerDSYMZipPath to hockeyapp")
        val appBody = RequestBody.create(MediaType.parse("application/octet-stream"), File(hstrackerAppZipPath))
        val dSYMBody = RequestBody.create(MediaType.parse("application/octet-stream"), File(hstrackerDSYMZipPath))
        val builder = MultipartBody.Builder()
            .setType(MediaType.parse("multipart/form-data")!!)
            .addFormDataPart("status", "2")
            .addFormDataPart("notify", "0")
            .addFormDataPart("strategy", "replace")
            .addFormDataPart("notes", markdown)
            .addFormDataPart("notes_type", "1")
            .addFormDataPart("ipa", File(hstrackerAppZipPath).name, appBody)
            .addFormDataPart("dsym", File(hstrackerDSYMZipPath).name, dSYMBody)

        val request = Request.Builder()
            .header("X-HockeyAppToken", config.hockey_app_token)
            .post(builder.build())
            .url("https://rink.hockeyapp.net/api/2/apps/$hockeyAppAppId/app_versions/upload")
            .build()

        val okHttpClient = OkHttpClient.Builder()
            .build()
        val response = okHttpClient
            .newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HockeyApp error: ${response.body()?.string()}")
        }
    }


    fun makeGithubRelease(versionName: String, markdown: String) {
        val input = mapOf(
            "tag_name" to versionName,
            "body" to markdown,
            "draft" to false,
            "prerelease" to false
        )

        val request = Request.Builder()
            .post(RequestBody.create(MediaType.parse("application/json"), mapAdapter.toJson(input)))
            .url("https://api.github.com/repos/Hearthsim/HSTracker/releases?access_token=${config.github_token}")
            .build()
        val response = OkHttpClient().newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("cannot create github release: ${response.body()?.string()}")
        }

        val responseString = response.body()!!.string()
        System.out.println("github returned: $responseString")
        val release = mapAdapter.fromJson(responseString)!!

        val uploadUrl = UriTemplate.fromTemplate(release["upload_url"] as String)
            .set("name", "HSTracker.app.zip")
            .expand()

        val request2 = Request.Builder()
            .post(RequestBody.create(MediaType.parse("application/zip"), File(hstrackerAppZipPath)))
            .url("$uploadUrl&access_token=${config.github_token}")
            .build()

        System.out.println("POST HSTracker.app.zip to Github")
        val response2 = okHttpClient.newCall(request2).execute()
        if (!response2.isSuccessful) {
            throw Exception("cannot uploading file: ${response2.body()?.string()}")
        }
    }

    private fun zip(input: String, output: String) {
        // We change the working directory so that zip produces correct archives
        runCommand(File(input).parentFile.absolutePath, "zip -r -y $output ${File(input).name}")
    }

    private fun runCommand(workingDir: String, command: String) {
        runArgs(workingDir, true, *command.split(" ").toTypedArray())
    }

    private fun getCommandOutput(workingDir: String, command: String): String {
        val inputStream = runArgs(workingDir, true, *command.split(" ").toTypedArray())
        return inputStream.bufferedReader().readText()
    }

    private fun runArgs(workingDir: String, withOutput: Boolean, vararg args: String): InputStream {
        System.out.println("Execute: " + args.joinToString(" "))
        if (!dryRun) {
            val builder = ProcessBuilder(*args)
                .directory(File(workingDir))
                .redirectError(ProcessBuilder.Redirect.INHERIT)

            if (withOutput) {
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }
            val process = builder.start()
            val ret = process.waitFor()

            if (ret != 0) {
                throw java.lang.Exception("command ${args.joinToString(" ")} failed")
            }
            return process.inputStream
        } else {
            return ByteArrayInputStream(ByteArray(0))
        }

    }
}