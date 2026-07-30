/**
 * Korean message catalog.
 *
 * `en.js` must carry exactly the same key set; a test asserts it, because a
 * catalog that has drifted renders a raw key path to a user in one language and
 * nowhere else, which is the kind of defect nobody notices until a customer does.
 */
export default {
  common: {
    loading: '불러오는 중…',
    retry: '다시 시도',
    cancel: '취소',
    close: '닫기',
    back: '뒤로',
    save: '저장',
    saving: '저장 중…',
    none: '없음',
    language: '언어 변경',
    signOut: '로그아웃',
    dismiss: '알림 닫기',
  },

  session: {
    signInTitle: '로그인',
    email: '이메일',
    password: '비밀번호',
    signIn: '로그인',
    signingIn: '로그인 중…',
    checking: '세션 확인 중…',
    setupTitle: '서버 초기 설정',
    setupHint: '이 서버에는 아직 관리자가 없습니다. 첫 관리자 계정을 만드세요.',
    setupSubmit: '관리자 만들기',
    setupSubmitting: '만드는 중…',
    passwordConfirm: '비밀번호 확인',
    passwordMismatch: '비밀번호가 일치하지 않습니다',
    throttled: '{seconds}초 후에 다시 시도할 수 있습니다',
  },

  stream: {
    live: '실시간 연결됨',
    connecting: '연결 중…',
    retrying: '재연결 대기 중…',
    superseded: '다른 탭에서 연결을 가져갔습니다',
    idle: '연결 없음',
    resynced: '놓친 변경이 있어 화면을 새로 읽었습니다',
  },

  errors: {
    // Keyed by the native `code`. The server's own `message` is never rendered:
    // it is English prose, and this UI ships in Korean and English.
    authentication_required: '세션이 만료되었습니다. 다시 로그인하세요.',
    invalid_credentials: '이메일 또는 비밀번호가 올바르지 않습니다',
    rate_limit_exceeded: '시도가 너무 많습니다. 잠시 후 다시 시도하세요.',
    stale_progress: '다른 기기에서 더 앞까지 읽었습니다',
    library_unavailable: '라이브러리 저장소에 접근할 수 없습니다',
    library_root_missing: '지정한 경로가 존재하지 않습니다',
    library_root_not_directory: '지정한 경로가 디렉터리가 아닙니다',
    library_root_overlap: '다른 라이브러리 경로와 겹칩니다',
    library_name_conflict: '같은 이름의 라이브러리가 이미 있습니다',
    server_already_claimed: '이미 설정이 완료된 서버입니다',
    event_stream_capacity: '서버의 실시간 연결이 한계에 도달했습니다',
    invalid_request: '요청 내용이 올바르지 않습니다',
    // A rejected query is a client defect, so there is nothing for the user to
    // correct — the text says what happened without implying they can fix it.
    invalid_query: '이 화면이 잘못된 요청을 보냈습니다',
    forbidden: '권한이 없습니다',
    not_found: '대상을 찾을 수 없습니다',
    network_unreachable: '서버에 연결할 수 없습니다',
    unknown: '알 수 없는 오류가 발생했습니다',
  },
}
