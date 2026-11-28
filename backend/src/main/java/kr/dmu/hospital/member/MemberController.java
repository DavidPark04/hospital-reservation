package kr.dmu.hospital.member;

import kr.dmu.hospital.auth.CurrentUser;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/members")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public Document list(@RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return members.search(CurrentUser.requireAdmin().getId(), type, q, page, pageSize);
    }

    @DeleteMapping("/{id}")
    public Document delete(@PathVariable String id) {
        return members.delete(CurrentUser.requireAdmin().getId(), id);
    }
}
