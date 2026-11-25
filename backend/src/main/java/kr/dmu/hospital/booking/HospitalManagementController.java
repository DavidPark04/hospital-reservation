package kr.dmu.hospital.booking;

import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hospital/manage")
public class HospitalManagementController {
    private final BookingService booking;

    public HospitalManagementController(BookingService booking) { this.booking = booking; }

    @GetMapping("/hospital")
    public Document hospital() { return booking.managedHospital(); }

    @GetMapping("/doctors")
    public Map<String, List<Document>> doctors() { return Map.of("items", booking.managedDoctors()); }

    @PostMapping("/doctors")
    public Document createDoctor(@RequestBody Map<String, Object> body) {
        return booking.createDoctor(text(body, "name"), text(body, "departmentCode"), optionalText(body, "specialty"));
    }

    @PatchMapping("/doctors/{id}")
    public Document editDoctor(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return booking.editDoctor(id, text(body, "name"), text(body, "departmentCode"), optionalText(body, "specialty"), bool(body, "active"));
    }

    @GetMapping("/slots")
    public Map<String, List<Document>> slots(@RequestParam String doctorId, @RequestParam String date) {
        return Map.of("items", booking.managedSlots(doctorId, date));
    }

    @PostMapping("/slots")
    public Map<String, List<Document>> createSlots(@RequestBody Map<String, Object> body) {
        return Map.of("items", booking.createManagedSlots(text(body, "doctorId"), text(body, "date"), text(body, "startTime"),
                text(body, "endTime"), integer(body, "intervalMinutes"), integer(body, "capacity")));
    }

    @PatchMapping("/slots/{id}")
    public Document editSlot(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return booking.editManagedSlot(id, integer(body, "capacity"), bool(body, "closed"));
    }

    @GetMapping("/reservations")
    public Map<String, List<Document>> reservations(@RequestParam(required = false) String date, @RequestParam(required = false) String status) {
        return Map.of("items", booking.managedReservations(date, status));
    }

    @PatchMapping("/reservations/{id}")
    public Document changeReservation(@PathVariable String id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                      @RequestBody Map<String, Object> body) {
        return booking.changeManagedReservation(id, key, text(body, "slotId"));
    }

    @DeleteMapping("/reservations/{id}")
    public Document cancelReservation(@PathVariable String id, @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return booking.cancelManagedReservation(id, key);
    }

    private String text(Map<String, Object> body, String name) {
        if (body == null || !(body.get(name) instanceof String value) || value.isBlank() || value.length() > 200) {
            throw new ApiException(400, "필수 입력을 확인해 주세요: " + name);
        }
        return value.trim();
    }

    private String optionalText(Map<String, Object> body, String name) {
        if (body == null || body.get(name) == null) return "";
        if (!(body.get(name) instanceof String value) || value.length() > 200) throw new ApiException(400, "입력 값을 확인해 주세요: " + name);
        return value.trim();
    }

    private int integer(Map<String, Object> body, String name) {
        if (body == null || !(body.get(name) instanceof Number value) || value.doubleValue() != value.intValue()) {
            throw new ApiException(400, "숫자 입력을 확인해 주세요: " + name);
        }
        return value.intValue();
    }

    private boolean bool(Map<String, Object> body, String name) {
        if (body == null || !(body.get(name) instanceof Boolean value)) throw new ApiException(400, "설정 값을 확인해 주세요: " + name);
        return value;
    }
}
