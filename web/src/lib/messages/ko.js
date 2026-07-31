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

  routes: {
    notFound: '없는 화면입니다',
    home: '처음으로',
  },

  admin: {
    title: '관리자 콘솔',
    backToReader: '리더로 돌아가기',
    notAdministrator: '관리자 권한이 없습니다. 아래 화면은 서버에서 거부됩니다.',
    dangerZone: '되돌릴 수 없는 작업',
    irreversible: '이 작업은 되돌릴 수 없습니다.',
    typeToConfirm: '확인하려면 “{name}”을 그대로 입력하세요',
    nav: {
      overview: '개요',
      libraries: '라이브러리',
      tasks: '작업 큐',
    },
    overview: {
      ready: '서비스 상태',
      up: '정상',
      down: '응답 불가',
      uptime: '가동 시간',
      requests: '누적 요청',
      workers: '작업자 수',
    },
    tasks: {
      pending: '대기',
      running: '실행 중',
      dead: '실패',
      discardExplain:
        '두 작업은 서로 다른 것을 버립니다. 대기 중 작업을 버리면 아직 아무 작업자도 집지 않은 일이 사라지고, 실패한 작업을 버리면 재시도 횟수가 초기화됩니다.',
      discardUnclaimed: '대기 중 작업 버리기',
      discardDead: '실패한 작업 버리기',
      confirmTitle: '작업을 버릴까요?',
      confirmUnclaimed: '아직 시작되지 않은 작업 {count}건을 버립니다.',
      confirmDead: '영구 실패한 작업 {count}건을 삭제합니다.',
      confirmAction: '버리기',
      cleared: '{count}건을 버렸습니다',
    },
    libraries: {
      name: '이름',
      create: '라이브러리 추가',
      edit: '수정',
      createTitle: '라이브러리 추가',
      editTitle: '라이브러리 수정',
      location: '경로',
      scanning: '스캔 대상',
      scanInterval: '스캔 주기',
      scanOnStartup: '시작할 때 스캔',
      scanCbx: '만화 아카이브(CBZ/CBR)',
      scanPdf: 'PDF',
      scanEpub: 'EPUB',
      replacementNote:
        '저장하면 라이브러리 설정 전체가 교체됩니다. 이 화면에 없는 설정은 지금 값 그대로 함께 보냅니다.',
      interval: {
        DISABLED: '자동 스캔 안 함',
        HOURLY: '1시간',
        EVERY_6H: '6시간',
        EVERY_12H: '12시간',
        DAILY: '1일',
        WEEKLY: '1주',
      },
      storage: '저장소',
      actions: '동작',
      available: '접근 가능',
      unavailable: '접근 불가',
      unavailableSince: '{at}부터 접근할 수 없습니다',
      none: '라이브러리가 없습니다',
      scan: '스캔',
      analyze: '분석',
      metadataRefresh: '메타데이터 갱신',
      recheck: '접근 여부 다시 확인',
      emptyTrash: '휴지통 비우기',
      delete: '삭제',
      taskAccepted: '{name}: {task} 요청을 접수했습니다',
      trashEmptying: '{name}의 휴지통 비우기를 접수했습니다',
      deleted: '{name}을 삭제했습니다',
      availableAgain: '{name}에 다시 접근할 수 있습니다',
      emptyTrashTitle: '휴지통을 비울까요?',
      emptyTrashSummary:
        '{name}에서 시리즈 {series}개와 항목 {mediaItems}개를 영구 삭제합니다.',
      deleteTitle: '라이브러리를 삭제할까요?',
      deleteSummary: '{name}의 카탈로그 항목을 모두 삭제합니다. 원본 파일은 지우지 않습니다.',
      refusedTitle: '저장소에 접근할 수 없어 거부되었습니다',
      refusedExplain:
        '{name}의 저장소를 읽을 수 없습니다. 마운트가 일시적으로 빠진 것이라면 삭제해서는 안 됩니다. 먼저 접근 여부를 다시 확인하세요.',
      forceDeleteTitle: '접근 불가 상태로 강제 삭제할까요?',
      forceDeleteAction: '강제 삭제',
      forceDeleteWarning:
        '저장소를 읽을 수 없는 상태에서 삭제합니다. 마운트가 잠시 빠진 것이라면 되살릴 수 없는 손실이 됩니다.',
    },
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
