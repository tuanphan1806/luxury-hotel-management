import { randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext } from "@playwright/test";

const DEMO_PASSWORD = "123456";
const TEST_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

type ApiEnvelope<T> = {
  data: T;
  message?: string;
};

type CreatedEntity = {
  id: number;
  imageUrl?: string;
  imageUrls?: string[];
  facilities?: Array<{ id: number; imageUrls?: string[] }>;
};

async function login(request: APIRequestContext, username: string) {
  const response = await request.post("/backend_proxy/auth/login", {
    data: { username, password: DEMO_PASSWORD },
  });
  expect(response.status()).toBe(200);
  const payload = await response.json() as { accessToken?: string };
  expect(payload.accessToken).toBeTruthy();
  return payload.accessToken!;
}

async function uploadImage(
  request: APIRequestContext,
  token: string,
  folder: "FACILITIES" | "ROOM_TYPES" | "GALLERY",
  name: string,
) {
  const response = await request.post("/backend_proxy/files/upload", {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      folder,
      file: {
        name,
        mimeType: "image/png",
        buffer: TEST_PNG,
      },
    },
  });
  expect(response.status(), await response.text()).toBe(200);
  const payload = await response.json() as { url?: string };
  expect(payload.url).toBeTruthy();
  return payload.url!;
}

test.describe("management CRUD, media ownership, and audit trail", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(({}, testInfo) => {
    test.skip(testInfo.project.name !== "desktop-chrome", "Stateful CRUD runs once against the local PostgreSQL database.");
  });

  test("ADMIN manages facility, room type, and gallery while STAFF remains read-only", async ({ request }) => {
    const adminToken = await login(request, "admin");
    const staffToken = await login(request, "staff2");
    const adminHeaders = { Authorization: `Bearer ${adminToken}` };
    const staffHeaders = { Authorization: `Bearer ${staffToken}` };
    const suffix = randomUUID().slice(0, 8);

    let facilityId: number | undefined;
    let roomTypeId: number | undefined;
    let galleryId: number | undefined;

    try {
      const invalidFacility = await request.post("/backend_proxy/api/facilities", {
        headers: adminHeaders,
        data: {},
      });
      expect(invalidFacility.status()).toBe(400);

      const forbiddenFacility = await request.post("/backend_proxy/api/facilities", {
        headers: staffHeaders,
        data: {
          facilityName: `QA STAFF ${suffix}`,
          facilityNameEn: `QA STAFF ${suffix}`,
          type: "PUBLIC",
          imageUrls: [],
        },
      });
      expect(forbiddenFacility.status()).toBe(403);

      const facilityImages = await Promise.all([
        uploadImage(request, adminToken, "FACILITIES", `qa-facility-a-${suffix}.png`),
        uploadImage(request, adminToken, "FACILITIES", `qa-facility-b-${suffix}.png`),
      ]);
      const createFacility = await request.post("/backend_proxy/api/facilities", {
        headers: adminHeaders,
        data: {
          facilityName: `Tiện nghi QA ${suffix}`,
          facilityNameEn: `QA Facility ${suffix}`,
          type: "PUBLIC",
          description: "Bản ghi kiểm thử sẽ được xóa sau khi xác minh.",
          descriptionEn: "A QA record removed after verification.",
          imageUrls: facilityImages,
        },
      });
      expect(createFacility.status(), await createFacility.text()).toBe(201);
      const facility = (await createFacility.json() as ApiEnvelope<CreatedEntity>).data;
      facilityId = facility.id;
      expect(facility.imageUrls).toEqual(facilityImages);
      expect(facility.imageUrl).toBe(facilityImages[0]);

      const updateFacility = await request.put(`/backend_proxy/api/facilities/${facilityId}`, {
        headers: adminHeaders,
        data: {
          facilityName: `Tiện nghi QA đã sửa ${suffix}`,
          facilityNameEn: `Updated QA Facility ${suffix}`,
          type: "IN_ROOM",
          description: "Đã xác minh cập nhật.",
          descriptionEn: "Update verified.",
          imageUrls: facilityImages,
        },
      });
      expect(updateFacility.status()).toBe(200);

      const roomTypeImages = await Promise.all([
        uploadImage(request, adminToken, "ROOM_TYPES", `qa-room-a-${suffix}.png`),
        uploadImage(request, adminToken, "ROOM_TYPES", `qa-room-b-${suffix}.png`),
        uploadImage(request, adminToken, "ROOM_TYPES", `qa-room-c-${suffix}.png`),
      ]);
      const createRoomType = await request.post("/backend_proxy/api/room-types", {
        headers: adminHeaders,
        data: {
          typeName: `Loại phòng QA ${suffix}`,
          typeNameEn: `QA Room Type ${suffix}`,
          description: "Loại phòng kiểm thử tạm thời.",
          descriptionEn: "Temporary QA room type.",
          price: 123456,
          maxGuests: 3,
          imageUrls: roomTypeImages,
          facilityIds: [facilityId],
        },
      });
      expect(createRoomType.status(), await createRoomType.text()).toBe(201);
      const roomType = (await createRoomType.json() as ApiEnvelope<CreatedEntity>).data;
      roomTypeId = roomType.id;
      expect(roomType.imageUrls).toEqual(roomTypeImages);
      expect(roomType.imageUrl).toBe(roomTypeImages[0]);
      expect(roomType.facilities?.map((item) => item.id)).toContain(facilityId);
      expect(roomType.facilities?.[0]?.imageUrls).toEqual(facilityImages);

      const updateRoomType = await request.put(`/backend_proxy/api/room-types/${roomTypeId}`, {
        headers: adminHeaders,
        data: {
          typeName: `Loại phòng QA đã sửa ${suffix}`,
          typeNameEn: `Updated QA Room Type ${suffix}`,
          description: "Đã xác minh cập nhật.",
          descriptionEn: "Update verified.",
          price: 234567,
          maxGuests: 4,
          imageUrls: roomTypeImages,
          facilityIds: [facilityId],
        },
      });
      expect(updateRoomType.status()).toBe(200);

      const galleryImage = await uploadImage(request, adminToken, "GALLERY", `qa-gallery-${suffix}.png`);
      const createGallery = await request.post("/backend_proxy/api/galleries", {
        headers: adminHeaders,
        data: {
          title: `Ảnh QA ${suffix}`,
          titleEn: `QA Gallery ${suffix}`,
          type: "PUBLIC",
          imageUrl: galleryImage,
        },
      });
      expect(createGallery.status(), await createGallery.text()).toBe(201);
      galleryId = ((await createGallery.json() as ApiEnvelope<CreatedEntity>).data).id;

      const updateGallery = await request.put(`/backend_proxy/api/galleries/${galleryId}`, {
        headers: adminHeaders,
        data: {
          title: `Ảnh QA đã sửa ${suffix}`,
          titleEn: `Updated QA Gallery ${suffix}`,
          type: "PUBLIC",
          imageUrl: galleryImage,
        },
      });
      expect(updateGallery.status()).toBe(200);

      expect((await request.delete(`/backend_proxy/api/room-types/${roomTypeId}`, { headers: adminHeaders })).status()).toBe(200);
      expect((await request.delete(`/backend_proxy/api/galleries/${galleryId}`, { headers: adminHeaders })).status()).toBe(200);
      expect((await request.delete(`/backend_proxy/api/facilities/${facilityId}`, { headers: adminHeaders })).status()).toBe(200);

      const expectedActions = new Map([
        ["FACILITY", ["FACILITY_CREATED", "FACILITY_UPDATED", "FACILITY_DELETED"]],
        ["ROOM_TYPE", ["ROOM_TYPE_CREATED", "ROOM_TYPE_UPDATED", "ROOM_TYPE_DELETED"]],
        ["GALLERY", ["GALLERY_CREATED", "GALLERY_UPDATED", "GALLERY_DELETED"]],
      ]);
      const targetIds = new Map([
        ["FACILITY", facilityId],
        ["ROOM_TYPE", roomTypeId],
        ["GALLERY", galleryId],
      ]);

      for (const [targetType, actions] of expectedActions) {
        const audit = await request.get(
          `/backend_proxy/api/admin/audit-logs?targetType=${targetType}&targetId=${targetIds.get(targetType)}&page=0&size=20`,
          { headers: adminHeaders },
        );
        expect(audit.status()).toBe(200);
        const content = (await audit.json() as ApiEnvelope<{ content: Array<{ action: string; actorRole: string }> }>).data.content;
        expect(content.map((item) => item.action)).toEqual(expect.arrayContaining(actions));
        expect(content.every((item) => item.actorRole === "ADMIN")).toBe(true);
      }

      roomTypeId = undefined;
      galleryId = undefined;
      facilityId = undefined;
    } finally {
      if (roomTypeId) await request.delete(`/backend_proxy/api/room-types/${roomTypeId}`, { headers: adminHeaders });
      if (galleryId) await request.delete(`/backend_proxy/api/galleries/${galleryId}`, { headers: adminHeaders });
      if (facilityId) await request.delete(`/backend_proxy/api/facilities/${facilityId}`, { headers: adminHeaders });
    }
  });
});
