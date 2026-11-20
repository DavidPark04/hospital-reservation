package kr.dmu.hospital.booking;

import kr.dmu.hospital.auth.CurrentUser;
import kr.dmu.hospital.common.ApiException;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final BookingService booking;

    public BookingController(BookingService booking) {
        this.booking = booking;
    }

    @GetMapping("/hospitals/{hospitalId}/doctors")
    public Map<String, List<Document>> doctors(@PathVariable String hospitalId,
                                              @RequestParam(defaultValue = "01") String department) {
        return Map.of("items", booking.doctors(hospitalId, department));
    }

    @GetMapping("/slots")
    public Map<String, List<Document>> slots(@RequestParam String doctorId, @RequestParam String date) {
        return Map.of("items", booking.slots(doctorId, date, false));
    }

    @GetMapping("/reservations")
    public Map<String, List<Document>> reservations() {
        return Map.of("items", booking.reservations(CurrentUser.require().getId()));
    }

    @PostMapping("/reservations")
    public Document create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                           @RequestBody Map<String, Object> body) {
        return booking.create(CurrentUser.require().getId(), key, text(body, "slotId"));
    }

    @PatchMapping("/reservations/{id}")
    public Document change(@PathVariable String id,
                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                           @RequestBody Map<String, Object> body) {
        return booking.change(CurrentUser.require().getId(), id, key, text(body, "slotId"));
    }

    @DeleteMapping("/reservations/{id}")
    public Document cancel(@PathVariable String id,
                           @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return booking.cancel(CurrentUser.require().getId(), id, key);
    }

    @GetMapping("/admin/slots")
    public Map<String, List<Document>> adminSlots(@RequestParam String doctorId, @RequestParam String date) {
        CurrentUser.requireAdmin();
        return Map.of("items", booking.slots(doctorId, date, true));
    }

    @PostMapping("/admin/slots")
    public Map<String, List<Document>> createSlots(@RequestBody Map<String, Object> body) {
        CurrentUser.requireAdmin();
        return Map.of("items", booking.createSlots(text(body, "doctorId"), text(body, "date"),
                text(body, "startTime"), text(body, "endTime"), integer(body, "intervalMinutes"),
                integer(body, "capacity")));
    }

    @PatchMapping("/admin/slots/{id}")
    public Document editSlot(@PathVariable String id, @RequestBody Map<String, Object> body) {
        CurrentUser.requireAdmin();
        if (!(body.get("closed") instanceof Boolean)) {
            throw new ApiException(400, "휴진 여부를 선택해 주세요.");
        }
        return booking.editSlot(id, integer(body, "capacity"), (Boolean) body.get("closed"));
    }

    private String text(Map<String, Object> body, String name) {
        if (!(body.get(name) instanceof String value) || value.isBlank() || value.length() > 200) {
            throw new ApiException(400, "필수 입력을 확인해 주세요: " + name);
        }
        return value.trim();
    }

    private int integer(Map<String, Object> body, String name) {
        if (!(body.get(name) instanceof Number value) || value.doubleValue() != value.intValue()) {
            throw new ApiException(400, "숫자 입력을 확인해 주세요: " + name);
        }
        return value.intValue();
    }
}
