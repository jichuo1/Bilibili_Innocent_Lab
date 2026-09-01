package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import android.content.Context
import android.text.Spanned
import android.view.View
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme

/**
 * 将受长度约束的 GitHub Release Markdown 渲染为原生 Spanned。
 *
 * 只依赖 Markwon Core：不注册 HTML、图片加载、语法高亮或 WebView 能力，因此排版本身
 * 不会产生额外网络请求。链接在点击时仍须经过 [ReleaseNotesLinkPolicy]。
 */
internal class ReleaseNotesMarkdownRenderer(
    context: Context,
    textColor: Int,
    linkColor: Int,
    codeBackgroundColor: Int,
    quoteColor: Int,
    private val onOpenHttpsLink: (String) -> Unit
) {
    private val markwon = Markwon.builder(context.applicationContext)
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .linkColor(linkColor)
                    .codeTextColor(textColor)
                    .codeBackgroundColor(codeBackgroundColor)
                    .blockQuoteColor(quoteColor)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver(object : LinkResolver {
                    override fun resolve(view: View, link: String) {
                        ReleaseNotesLinkPolicy.resolveDestination(link)?.let(onOpenHttpsLink)
                    }
                })
            }
        })
        .build()

    fun render(markdown: String): Presentation = runCatching {
        Presentation.Formatted(markwon.toMarkdown(markdown))
    }.getOrElse {
        Presentation.PlainText(markdown)
    }

    internal sealed interface Presentation {
        data class Formatted(val text: Spanned) : Presentation
        data class PlainText(val text: String) : Presentation
    }
}
