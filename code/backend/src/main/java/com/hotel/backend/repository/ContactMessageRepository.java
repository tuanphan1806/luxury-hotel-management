package com.hotel.backend.repository;

import com.hotel.backend.constant.ContactMessageStatus;
import com.hotel.backend.entity.ContactMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, Long> {
    List<ContactMessage> findAllByOrderByCreatedAtDesc();

    long countByStatusIn(Collection<ContactMessageStatus> statuses);
}
