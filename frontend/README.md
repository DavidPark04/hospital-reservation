# Frontend

React 19 · TypeScript · Vite · TanStack Query 앱입니다. 스크롤 애니메이션을 포함한 소개 화면, 상담·병원 검색·상세·예약, 일반/소셜 로그인, 추가 가입 정보, 마이페이지, 관리자 화면을 제공합니다.

## 실행과 빌드

Node.js 22를 사용합니다.

```bash
cd frontend
npm ci
npm run dev
```

개발 화면은 `http://127.0.0.1:5174`입니다. Vite가 `/api`, OAuth 시작·콜백 요청을 로컬 Spring 서버 `http://127.0.0.1:18081`로 전달하므로 백엔드도 실행해야 합니다.

```bash
npm run build
```

이 명령은 TypeScript 검사와 Vite production 빌드를 수행하고 `dist/`를 만듭니다. 현재 별도 lint·브라우저 자동 테스트 명령은 제공하지 않습니다. 빌드 성공을 실제 소셜 계정 로그인이나 예약 시연 성공으로 간주하지 않습니다.

## API와 화면 상태

`src/api.ts`에서 동일 출처의 `/api`를 호출합니다. 배포 환경에서는 Vite 개발 프록시 대신 Vercel 라우팅이 Spring API로 전달합니다. API 키·MongoDB URI·JWT 비밀값을 `VITE_` 변수나 브라우저 번들에 넣지 않습니다.

소셜 인증 후 추가 입력을 완료하기 전에는 가입 안내 화면만 표시하고 마이페이지·예약·관리자 기능에 접근하지 못합니다. 이름·이메일·전화번호는 직접 입력하며, 중복 가입이면 기존 일반/카카오/구글 가입 방식으로 로그인하도록 안내합니다. 관리자 회원 목록에는 가입 완료 회원만 표시합니다.

화면은 `src/App.tsx`, 소셜 가입은 `SocialLogin.tsx`·`SocialProfile.tsx`, 스크롤 소개는 `ScrollStory.tsx`, 공통 요청은 `api.ts`에 있습니다. 초기 기획의 features/pages/components 분리는 현재 전체 적용된 상태가 아니며, 기능 수정 범위에 맞춰 진행합니다.

배포 경로와 새로고침 검증은 [배포 안내](../infra/README.md), HTTP 응답은 [API 계약](../docs/api-contract.md)를 참고합니다.
