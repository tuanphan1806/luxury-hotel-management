package com.hotel.backend.service.Impl;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.AssignStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.request.RoomRequest;
import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.dto.response.RoomPageResponse;
import com.hotel.backend.dto.response.RoomResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoom;
import com.hotel.backend.entity.RoomMaintenanceLog;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.DuplicateResourceException;
import com.hotel.backend.exception.ResourceNotFoundException;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationRoomRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.RoomService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j(topic = "ROOM-SERVICE")
@RequiredArgsConstructor
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationRoomRepository reservationRoomRepository;
    private final ReservationAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse create(RoomRequest request) {
        log.info("Creating room with roomName={}", request.getRoomName());

        if (roomRepository.existsByRoomName(request.getRoomName())) {
            throw new DuplicateResourceException("Room", "roomName", request.getRoomName());
        }

        RoomType roomType = getRoomTypeById(request.getRoomTypeId());

        Room room = Room.builder()
                .roomName(request.getRoomName())
                .roomType(roomType)
                .floor(request.getFloor())
                .description(request.getDescription())
                .build();

        roomRepository.save(room);
        auditRoom(room, ReservationAuditAction.ROOM_CREATED,
                "Tạo phòng", null, roomSnapshot(room), null);
        log.info("Room created successfully with roomId={}", room.getId());
        return RoomResponse.from(room);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse update(Long id, RoomRequest request) {
        log.info("Updating roomId={}", id);

        Room room = getRoomById(id);
        Map<String, Object> oldValue = roomSnapshot(room);

        ensureRoomHasNoActiveStay(room, "Không thể sửa phòng đang có khách lưu trú");

        if (!room.getRoomName().equals(request.getRoomName())
                && roomRepository.existsByRoomName(request.getRoomName())) {
            throw new DuplicateResourceException("Room", "roomName", request.getRoomName());
        }

        RoomType roomType = getRoomTypeById(request.getRoomTypeId());

        room.setRoomName(request.getRoomName());
        room.setRoomType(roomType);
        room.setFloor(request.getFloor());
        room.setDescription(request.getDescription());

        roomRepository.save(room);
        auditRoom(room, ReservationAuditAction.ROOM_UPDATED,
                "Cập nhật thông tin phòng", oldValue, roomSnapshot(room), null);
        log.info("Update room successfully roomId={}", id);
        return RoomResponse.from(room);
    }

    @Override
    public RoomResponse getById(Long id) {
        log.info("Fetching roomId={}", id);
        return RoomResponse.from(getRoomById(id));
    }

    @Override
    public List<RoomResponse> getAll() {
        log.info("Fetching all rooms");
        List<RoomResponse> result = roomRepository.findAll()
                .stream()
                .map(RoomResponse::from)
                .toList();
        log.info("Found {} rooms", result.size());
        return result;
    }

    @Override
    public List<RoomResponse> search(String keyword, RoomStatus status, CleaningStatus cleaningStatus) {
        log.info("Searching rooms keyword={}, status={}, cleaningStatus={}", keyword, status, cleaningStatus);
        List<RoomResponse> result = roomRepository.search(keyword, status, cleaningStatus)
                .stream()
                .map(RoomResponse::from)
                .toList();
        log.info("Search found {} rooms", result.size());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getAvailableRoomsForReservation(Long reservationId, Long roomTypeId) {
        log.info("Fetching available rooms for reservationId={}, roomTypeId={}", reservationId, roomTypeId);

        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found"));

        List<Long> roomTypeIds = reservation.getRoomTypes().stream()
                .map(rrt -> rrt.getRoomType().getId())
                .distinct()
                .toList();

        if (roomTypeId != null) {
            if (!roomTypeIds.contains(roomTypeId)) {
                throw new ResourceNotFoundException("Room type not found in reservation");
            }
            roomTypeIds = List.of(roomTypeId);
        }

        List<RoomResponse> result = roomTypeIds.stream()
                .flatMap(id -> roomRepository.findAvailableRoomsForReservationRoomType(
                                reservationId,
                                id,
                                reservation.getCheckIn(),
                                reservation.getCheckOut())
                        .stream())
                .map(RoomResponse::from)
                .toList();

        log.info("Found {} available rooms for reservationId={}, roomTypeId={}",
                result.size(), reservationId, roomTypeId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        log.info("Deleting roomId={}", id);
        Room room = getRoomById(id);
        Map<String, Object> oldValue = roomSnapshot(room);
        ensureRoomHasNoActiveStay(room, "Không thể xóa phòng đang có khách lưu trú");
        if (reservationRoomRepository.existsByRoomId(room.getId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể xóa phòng đã có lịch sử reservation; hãy chuyển sang bảo trì nếu ngừng sử dụng");
        }
        roomRepository.delete(room);
        auditRoom(room, ReservationAuditAction.ROOM_DELETED,
                "Xóa phòng", oldValue, null, null);
        log.info("Delete room successfully roomId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse updateStatus(Long id, RoomStatus status) {
        log.info("Updating status roomId={}, status={}", id, status);
        Room room = getRoomByIdForUpdate(id);
        Map<String, Object> oldValue = roomSnapshot(room);
        ensureRoomHasNoActiveStay(room, "Phòng đang có khách; chỉ được chuyển phòng hoặc checkout");
        if (status == RoomStatus.CHECKED_IN || status == RoomStatus.BOOKED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "CHECKED_IN và BOOKED phải do flow reservation quản lý, không được đặt thủ công");
        }
        if (status == RoomStatus.MAINTENANCE) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Vui lòng dùng hành động bắt đầu bảo trì và nhập đầy đủ lý do");
        }
        if (room.getStatus() == RoomStatus.MAINTENANCE && status == RoomStatus.AVAILABLE) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Vui lòng dùng hành động hoàn tất bảo trì");
        }
        room.setStatus(status);
        roomRepository.save(room);
        auditRoom(room, ReservationAuditAction.ROOM_UPDATED,
                "Cập nhật trạng thái phòng", oldValue, roomSnapshot(room),
                Map.of("operation", "STATUS_CHANGE"));
        log.info("Update status successfully roomId={}", id);
        return RoomResponse.from(room);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse updateCleaningStatus(Long id, CleaningStatus cleaningStatus) {
        log.info("Updating cleaning status roomId={}, cleaningStatus={}", id, cleaningStatus);
        Room room = getRoomById(id);
        Map<String, Object> oldValue = roomSnapshot(room);
        room.setCleaningStatus(cleaningStatus);
        roomRepository.save(room);
        auditRoom(room, ReservationAuditAction.ROOM_UPDATED,
                "Cập nhật trạng thái dọn phòng", oldValue, roomSnapshot(room),
                Map.of("operation", "CLEANING_STATUS_CHANGE"));
        log.info("Update cleaning status successfully roomId={}", id);
        return RoomResponse.from(room);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse transferCheckedInRoom(Long sourceRoomId, Long targetRoomId) {
        if (sourceRoomId.equals(targetRoomId)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phòng chuyển đến phải khác phòng hiện tại");
        }

        Room firstLocked = getRoomByIdForUpdate(Math.min(sourceRoomId, targetRoomId));
        Room secondLocked = getRoomByIdForUpdate(Math.max(sourceRoomId, targetRoomId));
        Room sourceRoom = firstLocked.getId().equals(sourceRoomId) ? firstLocked : secondLocked;
        Room targetRoom = firstLocked.getId().equals(targetRoomId) ? firstLocked : secondLocked;
        Map<String, Object> sourceBefore = roomSnapshot(sourceRoom);
        Map<String, Object> targetBefore = roomSnapshot(targetRoom);
        ReservationRoom activeStay = reservationRoomRepository
                .findFirstByRoomIdAndStatus(sourceRoomId, AssignStatus.CHECKED_IN)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REQUEST,
                        "Không tìm thấy lượt lưu trú đang hoạt động tại phòng nguồn"));

        if (sourceRoom.getStatus() != RoomStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phòng nguồn không ở trạng thái đang có khách");
        }
        if (targetRoom.getStatus() != RoomStatus.AVAILABLE
                || targetRoom.getCleaningStatus() != CleaningStatus.CLEAN) {
            throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE, "Phòng chuyển đến phải đang sẵn sàng và đã dọn sạch");
        }
        if (!sourceRoom.getRoomType().getId().equals(targetRoom.getRoomType().getId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Chỉ được chuyển sang phòng cùng loại");
        }
        if (reservationRoomRepository.existsByRoomIdAndStatus(targetRoomId, AssignStatus.CHECKED_IN)) {
            throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE, "Phòng chuyển đến đã có lượt lưu trú khác");
        }

        activeStay.setRoom(targetRoom);
        reservationRoomRepository.save(activeStay);

        sourceRoom.setStatus(RoomStatus.AVAILABLE);
        sourceRoom.setCleaningStatus(CleaningStatus.DIRTY);
        targetRoom.setStatus(RoomStatus.CHECKED_IN);
        roomRepository.save(sourceRoom);
        roomRepository.save(targetRoom);

        Map<String, Object> transferDetail = Map.of(
                "operation", "TRANSFER_CHECKED_IN_ROOM",
                "reservationRoomId", activeStay.getId(),
                "sourceRoomId", sourceRoomId,
                "targetRoomId", targetRoomId);
        auditRoom(sourceRoom, ReservationAuditAction.ROOM_UPDATED,
                "Chuyển khách sang phòng khác", sourceBefore, roomSnapshot(sourceRoom), transferDetail);
        auditRoom(targetRoom, ReservationAuditAction.ROOM_UPDATED,
                "Nhận khách được chuyển phòng", targetBefore, roomSnapshot(targetRoom), transferDetail);

        log.info("Transferred active stay reservationRoomId={} from roomId={} to roomId={}",
                activeStay.getId(), sourceRoomId, targetRoomId);
        return RoomResponse.from(targetRoom);
    }

    @Override
    @Transactional(readOnly = true)
    public Long getActiveReservationId(Long roomId) {
        Room room = getRoomById(roomId);
        if (room.getStatus() != RoomStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phòng hiện không có khách đang lưu trú");
        }
        return reservationRoomRepository.findActiveReservationIdByRoomId(roomId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND,
                        "Không tìm thấy reservation đang lưu trú của phòng này"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse startMaintenance(Long roomId, RoomMaintenanceRequest request) {
        Room room = getRoomByIdForUpdate(roomId);
        Map<String, Object> oldValue = roomSnapshot(room);
        ensureRoomHasNoActiveStay(room, "Không thể bảo trì phòng đang có khách lưu trú");
        if (room.getStatus() == RoomStatus.MAINTENANCE) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phòng đã ở trạng thái bảo trì");
        }
        room.setStatus(RoomStatus.MAINTENANCE);
        room.setCleaningStatus(CleaningStatus.DIRTY);
        room.setMaintenanceReason(request.getReason().trim());
        room.setMaintenanceExpectedCompletedDate(request.getExpectedCompletedDate());
        addMaintenanceEntry(room, "Khởi tạo bảo trì", request.getReason().trim());
        Room saved = roomRepository.save(room);
        auditRoom(saved, ReservationAuditAction.ROOM_UPDATED,
                "Bắt đầu bảo trì phòng", oldValue, roomSnapshot(saved),
                Map.of("operation", "START_MAINTENANCE", "reason", request.getReason().trim()));
        return RoomResponse.from(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse addMaintenanceLog(Long roomId, String note) {
        Room room = getRoomByIdForUpdate(roomId);
        if (room.getStatus() != RoomStatus.MAINTENANCE) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Chỉ cập nhật nhật ký cho phòng đang bảo trì");
        }
        addMaintenanceEntry(room, "Cập nhật tiến độ", note.trim());
        Room saved = roomRepository.save(room);
        auditRoom(saved, ReservationAuditAction.ROOM_UPDATED,
                "Cập nhật tiến độ bảo trì", null, null,
                Map.of("operation", "MAINTENANCE_LOG_ADDED", "note", note.trim()));
        return RoomResponse.from(saved);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoomResponse completeMaintenance(Long roomId) {
        Room room = getRoomByIdForUpdate(roomId);
        Map<String, Object> oldValue = roomSnapshot(room);
        if (room.getStatus() != RoomStatus.MAINTENANCE) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phòng không ở trạng thái bảo trì");
        }
        addMaintenanceEntry(room, "Hoàn tất bảo trì",
                "Công tác bảo trì hoàn tất. Phòng chờ bộ phận dọn dẹp bàn giao.");
        room.setStatus(RoomStatus.AVAILABLE);
        room.setCleaningStatus(CleaningStatus.DIRTY);
        room.setMaintenanceReason(null);
        room.setMaintenanceExpectedCompletedDate(null);
        Room saved = roomRepository.save(room);
        auditRoom(saved, ReservationAuditAction.ROOM_UPDATED,
                "Hoàn tất bảo trì phòng", oldValue, roomSnapshot(saved),
                Map.of("operation", "COMPLETE_MAINTENANCE"));
        return RoomResponse.from(saved);
    }

    @Override
    public RoomPageResponse findAll(String keyword, String sort, int page, int size) {
        log.info("Fetching rooms keyword={}, sort={}, page={}, size={}", keyword, sort, page, size);

        Sort.Order order = new Sort.Order(Sort.Direction.ASC, "id");
        if (StringUtils.hasLength(sort)) {
            Pattern pattern = Pattern.compile("^(\\w+?)(:)(.*)");
            Matcher matcher = pattern.matcher(sort);
            if (matcher.find()) {
                String columnName = matcher.group(1);
                order = matcher.group(3).equalsIgnoreCase("asc")
                        ? new Sort.Order(Sort.Direction.ASC, columnName)
                        : new Sort.Order(Sort.Direction.DESC, columnName);
            }
        }

        int pageNo = page > 0 ? page - 1 : 0;
        Pageable pageable = PageRequest.of(pageNo, size, Sort.by(order));

        Page<Room> entityPage = StringUtils.hasLength(keyword)
                ? roomRepository.searchByKeyword("%" + keyword.toLowerCase() + "%", pageable)
                : roomRepository.findAll(pageable);

        log.info("Found {} rooms", entityPage.getTotalElements());
        return getRoomPageResponse(pageNo, size, entityPage);
    }

    private static RoomPageResponse getRoomPageResponse(int page, int size, Page<Room> rooms) {
        List<RoomResponse> roomList = rooms.stream()
                .map(RoomResponse::from)
                .toList();

        RoomPageResponse response = new RoomPageResponse();
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalElements(rooms.getTotalElements());
        response.setTotalPages(rooms.getTotalPages());
        response.setRooms(roomList);
        return response;
    }



    // ---- private helpers ----
    private Room getRoomById(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private Room getRoomByIdForUpdate(Long id) {
        return roomRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private void ensureRoomHasNoActiveStay(Room room, String message) {
        if (room.getStatus() == RoomStatus.CHECKED_IN
                || reservationRoomRepository.existsByRoomIdAndStatusIn(
                        room.getId(), List.of(AssignStatus.ASSIGNED, AssignStatus.CHECKED_IN))) {
            throw new AppException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    private void addMaintenanceEntry(Room room, String action, String note) {
        RoomMaintenanceLog entry = RoomMaintenanceLog.builder()
                .room(room)
                .action(action)
                .note(note)
                .build();
        room.getMaintenanceHistory().add(entry);
    }

    private void auditRoom(
            Room room,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            Map<String, ?> detail) {
        auditService.recordTarget(
                "ROOM",
                String.valueOf(room.getId()),
                action,
                details,
                oldValue,
                newValue,
                detail,
                null,
                null);
    }

    private Map<String, Object> roomSnapshot(Room room) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", room.getId());
        value.put("roomName", room.getRoomName());
        value.put("roomTypeId", room.getRoomType() != null ? room.getRoomType().getId() : null);
        value.put("floor", room.getFloor());
        value.put("status", room.getStatus() != null ? room.getStatus().name() : null);
        value.put("cleaningStatus", room.getCleaningStatus() != null
                ? room.getCleaningStatus().name() : null);
        value.put("maintenanceReason", room.getMaintenanceReason());
        value.put("maintenanceExpectedCompletedDate", room.getMaintenanceExpectedCompletedDate());
        return value;
    }

    private RoomType getRoomTypeById(Long id) {
        return roomTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found"));
    }
}
