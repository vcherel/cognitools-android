package com.example.myapp.mail

import android.content.Context
import android.util.Base64
import com.example.myapp.AppSnackbar
import com.example.myapp.userMessage
import com.sun.mail.imap.IMAPFolder
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class MailMessage(
    val uid: Long,
    val from: String,
    val fromAddress: String,
    val subject: String,
    val date: Long,
    val seen: Boolean,
    /** Null until the body has been fetched, which happens after the whole list is on screen. */
    val html: String? = null,
    val preview: String = "",
    val code: String? = null
)

data class MailState(
    val messages: List<MailMessage> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val needsLogin: Boolean = false
)

/**
 * The Yahoo inbox, fetched over IMAP only when asked: no background connection, no notification.
 * A fetch lists the latest messages first (one round trip), then reads their bodies newest first so
 * the codes appear on the rows one by one. The inbox is opened read only, so reading here never marks
 * a mail read on Yahoo's side; the one write is deleting, which moves a mail to Yahoo's Trash.
 */
class MailRepository(context: Context) {
    val settings = MailSettings(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MailState())
    val state: StateFlow<MailState> = _state.asStateFlow()

    // Bumped by every fetch, so a slower fetch it replaced cannot write its late results over it.
    @Volatile private var generation = 0

    fun refresh() {
        val gen = ++generation
        scope.launch {
            fun update(change: (MailState) -> MailState) {
                if (gen == generation) _state.update(change)
            }
            update { it.copy(loading = true, error = null) }
            try {
                val address = settings.address.first()
                val password = settings.password.first()
                if (address.isBlank() || password.isBlank()) {
                    update { it.copy(needsLogin = true) }
                    return@launch
                }
                update { it.copy(needsLogin = false) }
                withStore(address, password) { store ->
                    val folder = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
                    val known = _state.value.messages.associateBy { it.uid }
                    val headers = readHeaders(folder).map { header ->
                        known[header.uid]?.takeIf { it.html != null }?.copy(seen = header.seen) ?: header
                    }
                    update { it.copy(messages = headers) }
                    for (header in headers.filter { it.html == null }) {
                        ensureActive()
                        if (gen != generation) return@withStore
                        val message = (folder as UIDFolder).getMessageByUID(header.uid) ?: continue
                        val loaded = withBody(header, message)
                        update { state -> state.copy(messages = state.messages.map { if (it.uid == loaded.uid) loaded else it }) }
                    }
                }
            } catch (e: AuthenticationFailedException) {
                update { it.copy(error = "Identifiants refusés par Yahoo, vérifie le mot de passe d'application") }
            } catch (e: Exception) {
                val message = userMessage(e)
                update { it.copy(error = message) }
            } finally {
                update { it.copy(loading = false) }
            }
        }
    }

    /**
     * Moves the mail to Yahoo's Trash right away, off the list at once. The snackbar's Annuler moves
     * it back to the inbox; if the move fails the row comes back with the reason.
     */
    fun delete(uid: Long) {
        val removed = _state.value.messages.firstOrNull { it.uid == uid } ?: return
        _state.update { state -> state.copy(messages = state.messages.filter { it.uid != uid }) }
        scope.launch {
            try {
                val trashUid = withStore { store ->
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_WRITE)
                    val message = inbox.getMessageByUID(uid) ?: return@withStore null
                    inbox.moveUIDMessages(arrayOf(message), trashFolder(store)).firstOrNull()?.uid
                }
                if (trashUid == null) {
                    AppSnackbar.show("Mail supprimé")
                } else {
                    AppSnackbar.show("Mail supprimé", actionLabel = "Annuler") { restore(removed, trashUid) }
                }
            } catch (e: Exception) {
                val message = userMessage(e)
                putBack(removed)
                AppSnackbar.show("Suppression impossible : $message")
            }
        }
    }

    private suspend fun restore(removed: MailMessage, trashUid: Long) = withContext(Dispatchers.IO) {
        try {
            val inboxUid = withStore { store ->
                val trash = trashFolder(store)
                trash.open(Folder.READ_WRITE)
                val message = trash.getMessageByUID(trashUid) ?: return@withStore null
                trash.moveUIDMessages(arrayOf(message), store.getFolder("INBOX")).firstOrNull()?.uid
            }
            // Back in the inbox it has a new UID; without one (no UIDPLUS answer) a refetch finds it.
            if (inboxUid != null) putBack(removed.copy(uid = inboxUid)) else refresh()
        } catch (e: Exception) {
            AppSnackbar.show("Restauration impossible : ${userMessage(e)}")
        }
    }

    private fun putBack(message: MailMessage) = _state.update { state ->
        state.copy(messages = (state.messages.filter { it.uid != message.uid } + message).sortedByDescending { it.date })
    }

    // The folder Yahoo flags as the trash (special use attribute), by its usual name otherwise.
    private fun trashFolder(store: Store): IMAPFolder =
        store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
            .firstOrNull { folder -> folder.attributes.any { it.equals("\\Trash", ignoreCase = true) } }
            ?: store.getFolder("Trash") as IMAPFolder

    private suspend inline fun <T> withStore(block: (Store) -> T): T =
        withStore(settings.address.first(), settings.password.first(), block)

    // One IMAP session, closed with every folder it opened (closing a folder never expunges here).
    private inline fun <T> withStore(address: String, password: String, block: (Store) -> T): T {
        val props = Properties().apply {
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "20000")
            put("mail.imaps.peek", "true")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(IMAP_HOST, address, password)
        try {
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun readHeaders(folder: Folder): List<MailMessage> {
        val count = folder.messageCount
        if (count == 0) return emptyList()
        val messages = folder.getMessages(maxOf(1, count - FETCH_COUNT + 1), count)
        folder.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        })
        return messages.reversed().map { message ->
            val sender = message.from?.firstOrNull() as? InternetAddress
            val subject = message.subject.orEmpty()
            MailMessage(
                uid = (folder as UIDFolder).getUID(message),
                from = sender?.personal?.takeIf { it.isNotBlank() } ?: sender?.address.orEmpty(),
                fromAddress = sender?.address.orEmpty(),
                subject = subject,
                date = (message.receivedDate ?: message.sentDate)?.time ?: 0L,
                seen = message.isSet(Flags.Flag.SEEN),
                code = findVerificationCode(subject, "")
            )
        }
    }

    private fun withBody(header: MailMessage, message: Message): MailMessage {
        val body = runCatching { readBody(message) }.getOrNull()
        val html = when {
            body == null -> plainToHtml("(Contenu illisible)")
            body.isHtml -> inlineImages(body.text, body.images)
            else -> plainToHtml(body.text)
        }
        val text = if (body?.isHtml == true) Jsoup.parse(body.text).text() else body?.text.orEmpty()
        return header.copy(
            html = html,
            preview = text.replace(WHITESPACE, " ").trim().take(PREVIEW_LENGTH),
            code = findVerificationCode(header.subject, text)
        )
    }

    private class Body(val text: String, val isHtml: Boolean, val images: Map<String, String>)

    // The HTML alternative when there is one, else the plain text. Attachments are skipped, and the
    // pictures a multipart/related body refers to by Content-ID are kept to inline them.
    private fun readBody(part: Part): Body? {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
        return when {
            part.isMimeType("text/html") -> Body(part.textContent(), true, emptyMap())
            part.isMimeType("text/plain") -> Body(part.textContent(), false, emptyMap())
            part.isMimeType("message/rfc822") -> (part.content as? Part)?.let(::readBody)
            part.isMimeType("multipart/*") -> {
                val parts = (part.content as Multipart).let { mp -> (0 until mp.count).map(mp::getBodyPart) }
                val bodies = parts.mapNotNull { runCatching { readBody(it) }.getOrNull() }
                val chosen = if (part.isMimeType("multipart/alternative")) {
                    bodies.lastOrNull { it.isHtml } ?: bodies.lastOrNull()
                } else {
                    bodies.firstOrNull()
                }
                val images = if (part.isMimeType("multipart/related")) relatedImages(parts) else emptyMap()
                chosen?.let { Body(it.text, it.isHtml, it.images + images) }
            }
            else -> null
        }
    }

    private fun relatedImages(parts: List<Part>): Map<String, String> = parts.mapNotNull { part ->
        val id = part.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>', ' ') ?: return@mapNotNull null
        if (!part.isMimeType("image/*") || part.size > MAX_INLINE_IMAGE) return@mapNotNull null
        val bytes = runCatching { part.inputStream.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
        val type = part.contentType.substringBefore(';').trim().lowercase()
        id to "data:$type;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }.toMap()

    private fun Part.textContent(): String = runCatching { content as String }.getOrElse {
        // An unknown charset label: the bytes are most likely UTF-8 anyway.
        MimeUtility.decode(inputStream, "binary").use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun inlineImages(html: String, images: Map<String, String>): String =
        images.entries.fold(html) { acc, (id, dataUri) -> acc.replace("cid:$id", dataUri) }

    private fun plainToHtml(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val linked = URL.replace(escaped) { "<a href=\"${it.value}\">${it.value}</a>" }
        return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><div style=\"white-space:pre-wrap;font-family:sans-serif;word-break:break-word\">$linked</div>"
    }

    private companion object {
        const val IMAP_HOST = "imap.mail.yahoo.com"
        const val FETCH_COUNT = 30
        const val PREVIEW_LENGTH = 160
        const val MAX_INLINE_IMAGE = 300_000
        val WHITESPACE = Regex("\\s+")
        val URL = Regex("https?://[^\\s<>\"]+")
    }
}
