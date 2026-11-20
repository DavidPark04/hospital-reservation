package kr.dmu.hospital.member;

import kr.dmu.hospital.auth.CurrentUser;
import org.bson.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/members")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public Map<String, List<Document>> list() {
        return Map.of("items", members.list(CurrentUser.requireAdmin().getId()));
    }

    @DeleteMapping("/{id}")
    public Document delete(@PathVariable String id) {
        return members.delete(CurrentUser.requireAdmin().getId(), id);
    }
}
