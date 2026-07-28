package com.hotel.backend.repository;

/**
 * Projection dùng chung cho các truy vấn tổng hợp tồn phòng theo hạng phòng.
 *
 * <p>Giữ kết quả ở dạng {@link Long} vì JPQL {@code count}/{@code sum}
 * trả về số nguyên 64-bit trên PostgreSQL.</p>
 */
public interface RoomTypeQuantityProjection {

    Long getRoomTypeId();

    Long getQuantity();
}
