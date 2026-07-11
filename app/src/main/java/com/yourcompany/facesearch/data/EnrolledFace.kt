package com.yourcompany.facesearch.data

data class EnrolledFace(
    val id: String,
    val name: String,
    val embedding: FloatArray
) {
    // FloatArray doesn't get structural equals/hashCode for free; override
    // so this behaves correctly if it's ever compared or put in a Set.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrolledFace) return false
        return id == other.id && name == other.name && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}