package com.distriar.driver

fun normalizeOrderStatus(value: String?): String {
    val raw = value?.trim()?.lowercase().orEmpty()
    if (raw.isBlank()) return "recibido"
    val key = raw.replace(Regex("[\\s-]+"), "_")
    return when (key) {
        "nuevo", "new", "pendiente", "pending" -> "recibido"
        "seen", "viewed" -> "visto"
        "preparando", "preparing", "prepared" -> "preparado"
        "en_camino", "encamino", "delivering", "shipped" -> "enviado"
        "delivered" -> "entregado"
        "canceled", "cancelled" -> "cancelado"
        else -> key
    }
}

fun formatOrderStatusLabel(value: String?): String {
    return when (normalizeOrderStatus(value)) {
        "recibido" -> "Recibido"
        "visto" -> "Visto"
        "preparado" -> "Preparado"
        "enviado" -> "En camino"
        "entregado" -> "Entregado"
        "cancelado" -> "Cancelado"
        else -> value?.trim().orEmpty().ifBlank { "Recibido" }
    }
}

fun formatAddress(order: Order): String {
    val parts = mutableListOf<String>()
    val street = listOfNotNull(order.userCalle?.trim(), order.userNumeracion?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    if (street.isNotEmpty()) parts.add(street)
    order.userBarrio?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    order.userDepartment?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    order.userPostalCode?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    return if (parts.isEmpty()) "Dirección no disponible" else parts.joinToString(" • ")
}

fun orderIsDelivered(order: Order): Boolean {
    return normalizeOrderStatus(order.status) == "entregado"
}

fun latestDeliveryIssue(order: Order): DeliveryIssue? {
    val fromList = order.deliveryIssues?.lastOrNull()
    if (fromList != null) return fromList
    val issueType = order.lastDeliveryIssueType?.takeIf { it.isNotBlank() } ?: return null
    return DeliveryIssue(
        type = issueType,
        note = order.lastDeliveryIssueNote,
        photoUrl = order.lastDeliveryIssuePhotoUrl,
        createdAt = order.lastDeliveryIssueAt,
        reportedById = null,
        reportedByUsername = null,
        closedAttempt = order.closedAttempts,
    )
}

fun formatLatestIssueSummary(order: Order): String? {
    val issue = latestDeliveryIssue(order)
    if (issue == null) {
        return order.cancelReason?.takeIf { it.isNotBlank() }
    }
    return when (issue.type?.trim()?.lowercase()) {
        "negocio_cerrado" -> {
            val attempts = issue.closedAttempt ?: order.closedAttempts ?: 0
            val base = if (attempts >= 2) {
                "Negocio cerrado: pedido cancelado"
            } else {
                "Negocio cerrado: vuelve al final de la ruta"
            }
            val note = issue.note?.takeIf { it.isNotBlank() } ?: order.cancelReason?.takeIf { it.isNotBlank() }
            if (note != null) "$base. $note" else base
        }
        "problema" -> {
            val note = issue.note?.takeIf { it.isNotBlank() } ?: return "Problema reportado"
            "Problema: $note"
        }
        else -> issue.note?.takeIf { it.isNotBlank() }
    }
}

fun formatAddressForDirections(order: Order): String {
    val parts = mutableListOf<String>()
    val street = listOfNotNull(order.userCalle?.trim(), order.userNumeracion?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    if (street.isNotEmpty()) parts.add(street)
    order.userBarrio?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    order.userDepartment?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
    if (parts.isNotEmpty()) {
        parts.add("Mendoza, Argentina")
        return parts.joinToString(", ")
    }
    val mapsQuery = extractMapsQuery(order.mapsUrl)
    return mapsQuery ?: ""
}

private fun extractMapsQuery(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val uri = android.net.Uri.parse(url)
        uri.getQueryParameter("query") ?: uri.getQueryParameter("destination")
    } catch (_: Exception) {
        null
    }
}
