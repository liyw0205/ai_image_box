package com.aiimagebox.provider

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object ProviderErrorLocalizer {
    fun httpError(status: Int, rawMessage: String): String {
        val reason = when (status) {
            400 -> "请求参数不被接口接受，请检查模型、尺寸、数量和扩展 JSON。"
            401 -> "认证失败，请检查 API Key 是否正确、是否过期。"
            403 -> "权限不足，请检查账号权限、模型授权或接口安全策略。"
            404 -> "接口或模型不存在，请检查 Base URL、/v1 路径和模型名。"
            408 -> "接口等待超时，请稍后重试或调大渠道超时。"
            409 -> "接口状态冲突，请稍后重试。"
            413 -> "请求体过大，请降低图片数量、尺寸或参考图大小。"
            415 -> "接口不支持当前请求格式，请检查 provider 类型和接口兼容性。"
            422 -> "请求字段校验失败，请检查模型、尺寸、比例和额外参数。"
            429 -> "请求过于频繁或额度不足，请稍后重试或切换渠道。"
            in 500..599 -> "服务端错误，通常需要稍后重试或切换 provider。"
            else -> "接口返回非成功状态。"
        }
        return withRaw("HTTP $status：$reason", rawMessage)
    }

    fun networkError(error: Throwable): String {
        val message = error.message.orEmpty().ifBlank { error::class.java.simpleName }
        val reason = when (error) {
            is UnknownHostException -> "无法解析接口域名，请检查 Base URL、网络或代理。"
            is SocketTimeoutException -> "请求超时，请检查网络、接口负载或调大渠道超时。"
            is ConnectException -> "无法连接接口，请检查 Base URL、端口、代理或本地服务是否运行。"
            is SSLException -> "TLS/证书握手失败，请检查 HTTPS 证书、代理或系统时间。"
            is IOException -> "网络请求失败，请检查网络、代理和接口地址。"
            else -> localMessage(message)
        }
        return withRaw(reason, message)
    }

    fun localMessage(rawMessage: String): String {
        val message = rawMessage.trim()
        val lower = message.lowercase()
        val reason = when {
            message.isBlank() -> "接口没有返回明确错误。"
            "invalid base url" in lower -> "Base URL 无效，请填写完整的 http/https 地址。"
            "prompt is required" in lower -> "提示词不能为空。"
            "only supports text_to_image" in lower -> "当前适配器只支持文生图。"
            "did not contain b64_json or url" in lower -> "接口响应里没有识别到图片 base64 或图片 URL。"
            "all downloads or decodes failed" in lower -> "接口返回了图片引用，但下载或解码全部失败。"
            "image download http" in lower -> "图片下载接口返回失败状态。"
            "downloaded content is not an image" in lower -> "下载结果不是图片内容。"
            "image exceeds max size" in lower -> "图片文件超过当前安全大小限制。"
            else -> return message
        }
        return withRaw(reason, message)
    }

    private fun withRaw(reason: String, raw: String): String {
        val cleanRaw = raw.trim()
        return if (cleanRaw.isBlank() || cleanRaw == reason) {
            reason
        } else {
            "$reason\n原始错误：$cleanRaw"
        }
    }
}
