package com.hotel.backend.config;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Facility;
import com.hotel.backend.entity.Gallery;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.GalleryRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seed dữ liệu master: Facility -> RoomType (gán facility) -> Room -> Gallery -> User.
 * Không seed dữ liệu giao dịch (reservations, payments, guests, reviews, room_holds,
 * chat_messages...) vì đó là dữ liệu runtime/demo, nên tạo qua API hoặc test data riêng.
 * Không dùng Role/Permission/Group — phân quyền dựa trực tiếp vào UserType (xem User#getAuthorities).
 */
@Component
@ConditionalOnProperty(name = "app.seed.master-data-enabled", havingValue = "true")
@RequiredArgsConstructor
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final String DEMO_PASSWORD = "123456";

    /**
     * URL gốc của bộ ảnh seed. Dev có thể dùng static của backend, còn
     * production dùng Cloudinary sau khi chạy migration ảnh một lần.
     */
    @Value("${app.seed-media.base-url}")
    private String seedMediaBaseUrl;

    @Value("${app.seed.demo-users-enabled:false}")
    private boolean demoUsersEnabled;

    private final FacilityRepository facilityRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final GalleryRepository galleryRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        Map<String, Facility> facilities = seedFacilities();
        Map<String, RoomType> roomTypes = seedRoomTypes(facilities);
        seedRooms(roomTypes);
        seedGalleries();
        if (demoUsersEnabled) {
            seedUsers();
        }
    }

    // ==================== FACILITIES ====================

    private Map<String, Facility> seedFacilities() {
        Map<String, Facility> result = new HashMap<>();

        result.put("pool", seedFacility("Hồ bơi", "Swimming Pool", "PUBLIC",
                "Bơi lội thư giãn trong hồ bơi riêng của khách sạn.",
                "Swim in a private pool with your friends!",
                List.of(
                        staticUrl("/facilities/fasilitas-1.jpg"),
                        staticUrl("/facilities/facility-pool-detail.webp"))));
        result.put("library", seedFacility("Thư viện", "Library", "PUBLIC",
                "Không gian đọc sách yên tĩnh với kho sách dành cho khách.",
                "Read as many books as you want for free here!",
                List.of(
                        staticUrl("/facilities/fasilitas-2.jpg"),
                        staticUrl("/facilities/facility-library-detail.webp"))));
        result.put("marketplace", seedFacility("Khu mua sắm", "Marketplace", "PUBLIC",
                "Khu mua sắm thuận tiện ngay trong khuôn viên khách sạn.",
                "Shop easily in the market that we have provided!",
                List.of(
                        staticUrl("/facilities/fasilitas-3.jpg"),
                        staticUrl("/facilities/facility-marketplace-detail.webp"))));
        result.put("kitchen", seedFacility("Bếp riêng", "Kitchen", "ROOM",
                "Bếp riêng đầy đủ tiện nghi để khách tự chuẩn bị món ăn.",
                "Want a private kitchen in your room? We've provided it!",
                List.of(
                        staticUrl("/facilities/fasilitas-4.jpg"),
                        staticUrl("/facilities/facility-kitchen-detail.webp"))));
        result.put("cafe", seedFacility("Quán cà phê", "Cafe", "PUBLIC",
                "Thư giãn và ngắm cảnh tại quán cà phê của khách sạn.",
                "Rest by looking at the beautiful scenery at our cafe!",
                List.of(
                        staticUrl("/facilities/fasilitas-5.jpg"),
                        staticUrl("/facilities/facility-cafe-detail.webp"))));
        result.put("bathroom", seedFacility("Phòng tắm riêng", "Bathroom", "ROOM",
                "Phòng tắm riêng sạch sẽ với đầy đủ trang thiết bị.",
                "Clean up in our fully equipped private bathroom!",
                List.of(
                        staticUrl("/facilities/fasilitas-6.jpg"),
                        staticUrl("/facilities/facility-bathroom-detail.webp"))));
        result.put("livingRoom", seedFacility("Phòng khách", "Living room", "ROOM",
                "Không gian phòng khách riêng để nghỉ ngơi và tiếp bạn bè.",
                "Welcome your friends to your hotel room's living room!",
                List.of(
                        staticUrl("/facilities/fasilitas-7.jpg"),
                        staticUrl("/facilities/facility-living-room-detail.webp"))));
        result.put("spa", seedFacility("Spa & chăm sóc sức khỏe", "Spa & Wellness", "PUBLIC",
                "Không gian trị liệu riêng tư với liệu trình thư giãn và chăm sóc cơ thể theo lịch hẹn.",
                "A private treatment space offering scheduled relaxation and wellness services.",
                List.of(
                        staticUrl("/facilities/facility-spa-wellness.webp"),
                        staticUrl("/facilities/facility-spa-detail.webp"))));
        result.put("fitness", seedFacility("Trung tâm thể hình", "Fitness Center", "PUBLIC",
                "Không gian tập luyện hiện đại với thiết bị cardio và tạ phục vụ khách lưu trú.",
                "A modern workout space with cardio and strength equipment for staying guests.",
                List.of(
                        staticUrl("/facilities/facility-fitness-center.webp"),
                        staticUrl("/facilities/facility-fitness-detail.webp"))));

        return result;
    }

    private Facility seedFacility(String name, String nameEn, String type, String description,
                                  String descriptionEn, List<String> imageUrls) {
        Facility facility = facilityRepository.findByFacilityNameIgnoreCase(name)
                .or(() -> facilityRepository.findByFacilityNameIgnoreCase(nameEn))
                .orElseGet(() -> Facility.builder()
                        .facilityName(name)
                        .build());

        facility.setFacilityName(name);
        facility.setFacilityNameEn(nameEn);
        facility.setType(type);
        facility.setDescription(description);
        facility.setDescriptionEn(descriptionEn);
        facility.setImageUrls(new ArrayList<>(imageUrls));
        facility.setImageUrl(imageUrls.isEmpty() ? null : imageUrls.get(0));
        return facilityRepository.save(facility);
    }

    // ==================== ROOM TYPES ====================
    // Facility gán cho từng room type theo bảng room_type_facilities trong SQL

    private Map<String, RoomType> seedRoomTypes(Map<String, Facility> facilities) {
        Map<String, RoomType> result = new HashMap<>();

        // Standard: tiện nghi cơ bản + không gian chung để ảnh facility phong phú hơn
        result.put("Standard", seedRoomType(
                "Phòng tiêu chuẩn", "Standard",
                "Phòng Standard tiện nghi đầy đủ, phù hợp cho cặp đôi hoặc du khách đơn lẻ.",
                "A well-equipped standard room, ideal for couples or solo travellers.",
                new BigDecimal("50000"),
                List.of(
                        staticUrl("/room_types/room-standard-main.webp"),
                        staticUrl("/room_types/7.jpg"),
                        staticUrl("/room_types/room-standard-detail.webp")),
                Set.of(
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("cafe"),
                        facilities.get("library")
                )
        ));

        // Deluxe: mở rộng thêm bếp, hồ bơi và cafe
        result.put("Deluxe", seedRoomType(
                "Phòng Deluxe", "Deluxe",
                "Phòng Deluxe rộng rãi với ban công view thành phố, nội thất sang trọng.",
                "A spacious deluxe room with a city-view balcony and refined interiors.",
                new BigDecimal("60000"),
                List.of(
                        staticUrl("/room_types/room-deluxe-detail.webp"),
                        staticUrl("/room_types/9.jpg"),
                        staticUrl("/room_types/8.jpg")),
                Set.of(
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("kitchen"),
                        facilities.get("pool"),
                        facilities.get("cafe")
                )
        ));

        // Executive: nằm giữa Deluxe và Suite, ưu tiên khách công tác/lưu trú ngắn ngày
        result.put("Executive", seedRoomType(
                "Phòng Executive", "Executive Room",
                "Phòng Executive dành cho khách công tác, có khu vực làm việc riêng, cửa sổ lớn và không gian thư giãn chỉn chu.",
                "An executive room for business travellers with a dedicated workspace, large windows and a refined relaxation area.",
                new BigDecimal("65000"),
                List.of(
                        staticUrl("/room_types/room-executive-main.webp"),
                        staticUrl("/room_types/room-executive-work.webp"),
                        staticUrl("/room_types/room-executive-bathroom.webp")),
                Set.of(
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("cafe"),
                        facilities.get("library"),
                        facilities.get("fitness")
                )
        ));

        // Suite: gần như đầy đủ tiện nghi nghỉ dưỡng
        result.put("Suite", seedRoomType(
                "Phòng Suite", "Suite",
                "Phòng Suite cao cấp với phòng khách riêng, bồn tắm jacuzzi và dịch vụ butler.",
                "A premium suite with a separate living room, jacuzzi and butler service.",
                new BigDecimal("70000"),
                List.of(
                        staticUrl("/room_types/room-suite-detail.webp"),
                        staticUrl("/room_types/12.jpg"),
                        staticUrl("/room_types/5.jpg")),
                Set.of(
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("kitchen"),
                        facilities.get("pool"),
                        facilities.get("library"),
                        facilities.get("cafe"),
                        facilities.get("spa")
                )
        ));

        // Family: ưu tiên các tiện nghi dùng chung và mua sắm thuận tiện
        result.put("Family", seedRoomType(
                "Phòng gia đình", "Family Room",
                "Phòng Family rộng lớn thiết kế cho gia đình, có 2 phòng ngủ và bếp nhỏ.",
                "A spacious family room with two bedrooms and a kitchenette.",
                new BigDecimal("80000"),
                List.of(
                        staticUrl("/room_types/room-family-detail.webp"),
                        staticUrl("/room_types/11.jpg"),
                        staticUrl("/room_types/10.jpg")),
                Set.of(
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("kitchen"),
                        facilities.get("pool"),
                        facilities.get("marketplace"),
                        facilities.get("cafe")
                )
        ));

        // Presidential Suite: tất cả facilities
        result.put("Presidential Suite", seedRoomType(
                "Phòng Tổng thống", "Presidential Suite",
                "Presidential Suite sang trọng bậc nhất với tầm nhìn panoramic 360 độ.",
                "Our most luxurious presidential suite with panoramic 360-degree views.",
                new BigDecimal("90000"),
                List.of(
                        staticUrl("/room_types/room-presidential-detail.webp"),
                        staticUrl("/room_types/13.jpg"),
                        staticUrl("/room_types/14.jpg")),
                Set.of(
                        facilities.get("pool"),
                        facilities.get("bathroom"),
                        facilities.get("livingRoom"),
                        facilities.get("kitchen"),
                        facilities.get("library"),
                        facilities.get("marketplace"),
                        facilities.get("cafe"),
                        facilities.get("spa"),
                        facilities.get("fitness")
                )
        ));

        return result;
    }

    private RoomType seedRoomType(String name, String nameEn, String desc, String descEn,
                                  BigDecimal price, List<String> imageUrls, Set<Facility> facilities) {
        RoomType rt = roomTypeRepository.findByTypeName(name)
                .or(() -> roomTypeRepository.findByTypeName(nameEn))
                .orElseGet(() -> RoomType.builder()
                        .typeName(name)
                        .build());

        rt.setTypeName(name);
        rt.setTypeNameEn(nameEn);
        rt.setDescription(desc);
        rt.setDescriptionEn(descEn);
        rt.setPrice(price);
        rt.setImageUrls(new ArrayList<>(imageUrls));
        rt.setImageUrl(imageUrls.isEmpty() ? null : imageUrls.get(0));
        rt.setFacilities(new HashSet<>(facilities));
        return roomTypeRepository.save(rt);
    }

    // ==================== ROOMS ====================

    private void seedRooms(Map<String, RoomType> roomTypes) {
        seedRoom("101", 1, "Phòng Standard hướng sân vườn", roomTypes.get("Standard"));
        seedRoom("102", 1, "Phòng Standard hướng sân vườn", roomTypes.get("Standard"));
        seedRoom("103", 1, "Phòng Standard hướng đường", roomTypes.get("Standard"));
        seedRoom("104", 1, "Phòng Standard hướng đường", roomTypes.get("Standard"));
        seedRoom("201", 2, "Phòng Standard tầng 2", roomTypes.get("Standard"));
        seedRoom("202", 2, "Phòng Deluxe view thành phố", roomTypes.get("Deluxe"));
        seedRoom("203", 2, "Phòng Deluxe view thành phố", roomTypes.get("Deluxe"));
        seedRoom("204", 2, "Phòng Deluxe góc ban công đôi", roomTypes.get("Deluxe"));
        seedRoom("205", 2, "Phòng Executive với khu vực làm việc riêng", roomTypes.get("Executive"));
        seedRoom("301", 3, "Phòng Deluxe tầng 3", roomTypes.get("Deluxe"));
        seedRoom("302", 3, "Phòng Family 2 phòng ngủ", roomTypes.get("Family"));
        seedRoom("303", 3, "Phòng Family view sân vườn", roomTypes.get("Family"));
        seedRoom("304", 3, "Phòng Deluxe tầng 3", roomTypes.get("Deluxe"));
        seedRoom("305", 3, "Phòng Executive view thành phố", roomTypes.get("Executive"));
        seedRoom("401", 4, "Suite với bồn tắm jacuzzi", roomTypes.get("Suite"));
        seedRoom("402", 4, "Suite view thành phố", roomTypes.get("Suite"));
        seedRoom("403", 4, "Suite góc panoramic", roomTypes.get("Suite"));
        seedRoom("501", 5, "Presidential Suite tầng thượng", roomTypes.get("Presidential Suite"));
    }

    private void seedRoom(String roomName, int floor, String description, RoomType roomType) {
        Room room = roomRepository.findByRoomName(roomName)
                .orElseGet(() -> Room.builder()
                        .roomName(roomName)
                        .status(RoomStatus.AVAILABLE)
                        .cleaningStatus(CleaningStatus.CLEAN)
                        .build());

        room.setFloor(floor);
        room.setDescription(description);
        room.setRoomType(roomType);

        if (room.getStatus() == null) {
            room.setStatus(RoomStatus.AVAILABLE);
        }
        if (room.getCleaningStatus() == null) {
            room.setCleaningStatus(CleaningStatus.CLEAN);
        }

        roomRepository.save(room);
    }

    // ==================== GALLERIES ====================

    private void seedGalleries() {
        seedGallery("Toàn cảnh khách sạn 1", "Hotel Building 1", "PUBLIC", staticUrl("/galeries/g-1.jpg"));
        seedGallery("Quán cà phê 1", "Café 1", "PUBLIC", staticUrl("/galeries/g-2.jpg"));
        seedGallery("Phòng khách trong phòng 1", "Hotel room living room 1", "ROOM", staticUrl("/galeries/g-3.jpg"));
        seedGallery("Phòng khách trong phòng 2", "Hotel room living room 2", "ROOM", staticUrl("/galeries/g-4.jpg"));
        seedGallery("Phòng khách sạn tông đỏ", "Hotel room with shades of red", "ROOM", staticUrl("/galeries/g-5.jpg"));
        seedGallery("Hồ bơi", "Swimming pool", "PUBLIC", staticUrl("/galeries/g-6.jpg"));
        seedGallery("Toàn cảnh khách sạn 2", "Hotel Building 2", "PUBLIC", staticUrl("/galeries/g-7.jpg"));
        seedGallery("Quán cà phê 2", "Café 2", "PUBLIC", staticUrl("/galeries/g-8.jpg"));
        seedGallery("Toàn cảnh khách sạn 3", "Hotel Building 3", "PUBLIC", staticUrl("/galeries/g-9.jpg"));
        seedGallery("Quán cà phê 3", "Café 3", "PUBLIC", staticUrl("/galeries/g-10.jpg"));
        seedGallery("Quán cà phê 4", "Café 4", "PUBLIC", staticUrl("/galeries/g-11.jpg"));
        seedGallery("Thư viện", "Library", "PUBLIC", staticUrl("/galeries/g-12.jpg"));
    }

    private void seedGallery(String title, String titleEn, String type, String imageUrl) {
        Gallery gallery = galleryRepository.findByTitleIgnoreCase(title)
                .or(() -> galleryRepository.findByTitleIgnoreCase(titleEn))
                .or(() -> galleryRepository.findByImageUrl(imageUrl))
                .orElseGet(() -> Gallery.builder()
                        .imageUrl(imageUrl)
                        .build());

        gallery.setTitle(title);
        gallery.setTitleEn(titleEn);
        gallery.setType(type);
        gallery.setImageUrl(imageUrl);
        galleryRepository.save(gallery);
    }

    // ==================== USERS ====================
    // Mật khẩu demo được đặt mới (SQL dump chỉ có hash, không phục hồi được plaintext).
    // Phân quyền dựa trực tiếp vào UserType (ADMIN/STAFF/CUSTOMER), không dùng Role/Group.

    private void seedUsers() {
        seedUser("Nguyễn Văn Admin", "admin", "admin@luxstay.vn", DEMO_PASSWORD,
                "0901000001", "12 Lý Thường Kiệt, Hà Nội", UserType.ADMIN, staticUrl("/avatar/1.png"));

        seedUser("Trần Thị Lan", "staff1", "staff1@luxstay.vn", DEMO_PASSWORD,
                "0901000002", "45 Trần Hưng Đạo, Hà Nội", UserType.STAFF, staticUrl("/avatar/2.png"));

        seedUser("Lê Văn Minh", "staff2", "staff2@luxstay.vn", DEMO_PASSWORD,
                "0901000003", "78 Nguyễn Huệ, TP.HCM", UserType.STAFF, staticUrl("/avatar/3.png"));

        seedUser("Hoàng Thị Thu", "staff_thu", "staff3@luxstay.vn", DEMO_PASSWORD,
                "0901000008", "33 Hai Bà Trưng, TP.HCM", UserType.STAFF, staticUrl("/avatar/4.png"));

        seedUser("Phạm Thị Hoa", "customer1", "customer1@gmail.com", DEMO_PASSWORD,
                "0901000004", "23 Hoàng Diệu, Đà Nẵng", UserType.CUSTOMER, staticUrl("/avatar/5.png"));

        seedUser("Nguyễn Quốc Hùng", "customer2", "customer2@gmail.com", DEMO_PASSWORD,
                "0901000005", "56 Lê Lợi, Hà Nội", UserType.CUSTOMER, staticUrl("/avatar/6.png"));

        seedUser("Vũ Thị Mai", "vtmai", "customer3@gmail.com", DEMO_PASSWORD,
                "0901000006", "90 Pasteur, TP.HCM", UserType.CUSTOMER, staticUrl("/avatar/7.png"));

        seedUser("Bùi Thị Ngọc", "btngocc", "customer5@gmail.com", DEMO_PASSWORD,
                "0901000009", "67 Phan Chu Trinh, Đà Nẵng", UserType.CUSTOMER, staticUrl("/avatar/8.png"));

        seedUser("Trương Văn Khoa", "tvkhoa", "customer6@gmail.com", DEMO_PASSWORD,
                "0901000010", "44 Võ Văn Tần, TP.HCM", UserType.CUSTOMER, staticUrl("/avatar/9.png"));

        // Tài khoản chưa xác minh email
        seedUnverifiedUser("Đặng Văn Tùng", "dvtung", "customer4@gmail.com", DEMO_PASSWORD,
                "0901000007", "11 Đinh Tiên Hoàng, Hà Nội", "VERIFY_ABC123", staticUrl("/avatar/0.png"));
    }

    private void seedUser(String fullName, String username, String email, String password,
                           String phone, String address, UserType type, String imageUrl) {
        User user = findExistingUser(username, email, phone);
        if (user == null) {
            user = User.builder()
                    .username(username)
                    .email(email)
                    .phone(phone)
                    .build();
        }

        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setAddress(address);
        user.setImageUrl(imageUrl);
        user.setType(type);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        userRepository.save(user);
        if (UserType.CUSTOMER.equals(type)) {
            ensureCustomerProfile(user);
        }
    }

    private void seedUnverifiedUser(String fullName, String username, String email, String password,
                                     String phone, String address, String verificationCode, String imageUrl) {
        User user = findExistingUser(username, email, phone);
        boolean isNewUser = user == null;
        if (user == null) {
            user = User.builder()
                    .username(username)
                    .email(email)
                    .phone(phone)
                    .build();
        }

        user.setPassword(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setUsername(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setAddress(address);
        user.setImageUrl(imageUrl);
        user.setType(UserType.CUSTOMER);
        if (isNewUser) {
            user.setStatus(UserStatus.PENDING_VERIFICATION);
            user.setEmailVerified(false);
            user.setVerificationCode(verificationCode);
        } else if (!user.isEmailVerified() && !UserStatus.ACTIVE.equals(user.getStatus())) {
            user.setStatus(UserStatus.PENDING_VERIFICATION);
            if (user.getVerificationCode() == null || user.getVerificationCode().isBlank()) {
                user.setVerificationCode(verificationCode);
            }
        }
        userRepository.save(user);
        ensureCustomerProfile(user);
    }

    private User findExistingUser(String username, String email, String phone) {
        return userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(email))
                .or(() -> userRepository.findByPhone(phone))
                .orElse(null);
    }

    private CustomerProfile ensureCustomerProfile(User user) {
        return customerProfileRepository.findByLinkedUserId(user.getId())
                .map(profile -> {
                    profile.setFullName(user.getFullName());
                    profile.setPhone(user.getPhone());
                    profile.setEmail(user.getEmail());
                    profile.setAddress(user.getAddress());
                    if (profile.getSource() == null) {
                        profile.setSource(CustomerProfileSource.ONLINE);
                    }
                    return customerProfileRepository.save(profile);
                })
                .orElseGet(() -> customerProfileRepository.save(CustomerProfile.builder()
                        .fullName(user.getFullName())
                        .phone(user.getPhone())
                        .email(user.getEmail())
                        .address(user.getAddress())
                        .source(CustomerProfileSource.ONLINE)
                        .linkedUser(user)
                        .build()));
    }

    private String staticUrl(String path) {
        if (seedMediaBaseUrl == null || seedMediaBaseUrl.isBlank()) {
            throw new IllegalStateException("app.seed-media.base-url must not be blank");
        }
        String normalizedBaseUrl = seedMediaBaseUrl.trim().replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBaseUrl + normalizedPath;
    }
}
