package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.DesignApproval
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import java.security.MessageDigest

/**
 * The owner must approve (or knowingly delegate approval of) the plain-English review before the authoritative spec is generated.
 * Approval is bound to a fingerprint of everything the owner can see in that review, so any later change - a correction, a new
 * upload, a withdrawn fact - makes the earlier approval stale and the review is shown again.
 */
object ReviewGate {

    fun fingerprint(p: Project): String {
        val sb = StringBuilder()
        p.decisions.toSortedMap().forEach { (k, d) ->
            // Bob's own routine defaults are not part of what the owner reviews.
            if (d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && d.prov != Provenance.DEFAULT && !(com.hotattic.gamedesigner.core.schema.Fields.get(k)?.derived == true && !d.ownerAuthored))
                sb.append(k).append('=').append(d.value).append(';')
        }
        p.activeFacts().forEach { sb.append("f:").append(it.text).append(';') }
        p.rejected.toSortedMap().forEach { (k, v) -> sb.append("r:").append(k).append(v.sorted()).append(';') }
        p.branding.toSortedMap().forEach { (k, b) -> sb.append("b:").append(k).append(b.mode).append(b.sha256).append(';') }
        return MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun approved(p: Project): Boolean = p.designApproval?.fingerprint == fingerprint(p)

    fun approve(p: Project, now: Long, by: String): Project = p.copy(designApproval = DesignApproval(fingerprint(p), now, by), updatedAt = now)
}
