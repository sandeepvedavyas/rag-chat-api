package com.sandeepvedavyas.rag.domain

class RequestRejectedException(
    val rejection: Rejection,
    message: String,
) : RuntimeException(message)

enum class Rejection {
    BAD_REQUEST,
    TOO_LARGE,
    UNSUPPORTED_MEDIA,
    NOT_FOUND,
    CONFLICT,
}
