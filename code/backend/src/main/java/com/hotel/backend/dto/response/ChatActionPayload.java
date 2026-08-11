package com.hotel.backend.dto.response;

/**
 * Marker for the small, explicitly supported action payloads returned by the
 * public chatbot. Keeping this closed prevents arbitrary internal objects from
 * accidentally becoming part of the public chat contract.
 */
public sealed interface ChatActionPayload permits ChatReservationPayload, ChatLinkPayload {
}
