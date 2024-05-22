package roomescape.reservation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import roomescape.global.exception.error.ErrorType;
import roomescape.global.exception.model.DataDuplicateException;
import roomescape.global.exception.model.ForbiddenException;
import roomescape.global.exception.model.NotFoundException;
import roomescape.global.exception.model.ValidateException;
import roomescape.member.domain.Member;
import roomescape.member.service.MemberService;
import roomescape.reservation.domain.MemberReservation;
import roomescape.reservation.domain.Reservation;
import roomescape.reservation.domain.ReservationStatus;
import roomescape.reservation.domain.ReservationTime;
import roomescape.reservation.domain.repository.MemberReservationRepository;
import roomescape.reservation.domain.repository.ReservationRepository;
import roomescape.reservation.domain.repository.ReservationTimeRepository;
import roomescape.reservation.dto.request.ReservationRequest;
import roomescape.reservation.dto.response.MemberReservationResponse;
import roomescape.reservation.dto.response.MemberReservationsResponse;
import roomescape.reservation.dto.response.ReservationResponse;
import roomescape.reservation.dto.response.ReservationTimeInfoResponse;
import roomescape.reservation.dto.response.ReservationTimeInfosResponse;
import roomescape.reservation.dto.response.ReservationsResponse;
import roomescape.theme.domain.Theme;
import roomescape.theme.domain.repository.ThemeRepository;
import roomescape.theme.service.ThemeService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final ReservationTimeRepository reservationTimeRepository;
    private final ReservationTimeService reservationTimeService;
    private final ThemeRepository themeRepository;
    private final MemberReservationRepository memberReservationRepository;
    private final MemberService memberService;
    private final ThemeService themeService;

    public ReservationService(final ReservationRepository reservationRepository,
                              final ReservationTimeRepository reservationTimeRepository, ReservationTimeService reservationTimeService,
                              final ThemeRepository themeRepository, MemberReservationRepository memberReservationRepository,
                              final MemberService memberService, ThemeService themeService) {
        this.reservationRepository = reservationRepository;
        this.reservationTimeRepository = reservationTimeRepository;
        this.reservationTimeService = reservationTimeService;
        this.themeRepository = themeRepository;
        this.memberReservationRepository = memberReservationRepository;
        this.memberService = memberService;
        this.themeService = themeService;
    }

    public ReservationsResponse findReservationsByStatus(final ReservationStatus status) {
        List<MemberReservation> memberReservations = memberReservationRepository.findByStatus(status);
        List<ReservationResponse> response = memberReservations.stream()
                .map(memberReservation -> ReservationResponse.from(memberReservation.getReservation()))
                .toList();
        return new ReservationsResponse(response);
    }

    public ReservationsResponse findFirstOrderWaitingReservations() {
        List<MemberReservation> waitingMemberReservations = memberReservationRepository.findByStatus(ReservationStatus.WAITING);
        List<ReservationResponse> response = waitingMemberReservations.stream()
                .filter(MemberReservation::isFirstWaitingOrder)
                .map(memberReservation -> ReservationResponse.from(memberReservation.getReservation()))
                .toList();

        return new ReservationsResponse(response);
    }

    public ReservationTimeInfosResponse findReservationsByDateAndThemeId(final LocalDate date, final Long themeId) {
        List<ReservationTime> allTimes = reservationTimeRepository.findAll();
        Theme theme = themeService.findThemeById(themeId);
        List<Reservation> reservations = reservationRepository.findByDateAndTheme(date, theme);

        List<ReservationTimeInfoResponse> response = new ArrayList<>();
        for (ReservationTime time : allTimes) {
            boolean alreadyBooked = false;
            for (Reservation reservation : reservations) {
                if (reservation.getReservationTime() == time) {
                    alreadyBooked = true;
                    break;
                }
            }
            response.add(new ReservationTimeInfoResponse(time.getId(), time.getStartAt(), alreadyBooked));
        }

        return new ReservationTimeInfosResponse(response);
    }

    public Reservation findReservationById(final Long id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorType.RESERVATION_NOT_FOUND,
                        String.format("예약(Reservation) 정보가 존재하지 않습니다. [reservationId: %d]", id)));
    }

    @Transactional
    public void removeReservationById(final Long reservationId, final Long requestMemberId) {
        Member member = memberService.findMemberById(requestMemberId);
        Reservation reservation = findReservationById(reservationId);
        MemberReservation memberReservation = findMemberReservationByReservation(reservation);

        Long reservationMemberId = reservation.getMember().getId();

        if (member.isAdmin() || reservationMemberId.equals(requestMemberId)) {
            memberReservationRepository.deleteById(memberReservation.getId());
            reservationRepository.deleteById(reservation.getId());
            changeOrdersStatus(reservation);
        } else {
            throw new ForbiddenException(ErrorType.PERMISSION_DOES_NOT_EXIST,
                    String.format("예약 정보에 대한 삭제 권한이 존재하지 않습니다. [reservationId: %d, memberReservationId: %d]"
                            , reservationId, memberReservation.getId()));
        }
    }

    @Transactional
    public void approveWaitingReservation(final Long reservationId) {
        Reservation reservation = findReservationById(reservationId);
        MemberReservation memberReservation = findMemberReservationByReservation(reservation);
        changeWaitingOrdersStatus(reservation);

        memberReservation.changeStatusToReserve();
    }

    private void changeOrdersStatus(final Reservation reservation) {
        List<MemberReservation> waitingReservations = memberReservationRepository.findByReservationTimeAndDateAndThemeOrderByIdAsc(reservation.getReservationTime(), reservation.getDate(), reservation.getTheme());
        for (MemberReservation waitingReservation : waitingReservations) {
            waitingReservation.increaseOrder();
            if (waitingReservation.isReserveOrder()) {
                waitingReservation.changeStatusToReserve();
            }
        }
    }

    @Transactional
    public void removeWaitingReservationById(final Long reservationId, final Long memberId) {
        Member member = memberService.findMemberById(memberId);
        Reservation reservation = findReservationById(reservationId);
        MemberReservation memberReservation = findMemberReservationByReservation(reservation);

        Long reservationMemberId = reservation.getMember().getId();

        if (member.isAdmin() || reservationMemberId.equals(memberId)) {
            memberReservationRepository.deleteById(memberReservation.getId());
            reservationRepository.deleteById(reservation.getId());
            changeWaitingOrdersStatus(reservation);
        } else {
            throw new ForbiddenException(ErrorType.PERMISSION_DOES_NOT_EXIST,
                    String.format("예약 정보에 대한 삭제 권한이 존재하지 않습니다. [reservationId: %d, memberReservationId: %d]"
                            , reservationId, memberReservation.getId()));
        }
    }

    private void changeWaitingOrdersStatus(final Reservation reservation) {
        List<MemberReservation> waitingReservations = memberReservationRepository.findByReservationTimeAndDateAndThemeOrderByIdAsc(reservation.getReservationTime(), reservation.getDate(), reservation.getTheme());
        for (MemberReservation waitingReservation : waitingReservations) {
            if (!waitingReservation.isReserved()) {
                waitingReservation.increaseOrder();
            }
        }
    }

    private MemberReservation findMemberReservationByReservation(final Reservation reservation) {
        return memberReservationRepository.findByReservation(reservation)
                .orElseThrow(() -> new NotFoundException(ErrorType.MEMBER_RESERVATION_NOT_FOUND,
                        ErrorType.MEMBER_RESERVATION_NOT_FOUND.getDescription()));
    }

    @Transactional
    public ReservationResponse addReservation(final ReservationRequest request, final Long memberId) {
        ReservationTime time = reservationTimeService.findTimeById(request.timeId());
        Theme theme = themeService.findThemeById(request.themeId());
        Member member = memberService.findMemberById(memberId);
        List<MemberReservation> alreadyBookedReservations = memberReservationRepository.findByReservationTimeAndDateAndThemeOrderByIdAsc(time, request.date(), theme);
        long order = alreadyBookedReservations.size();

        validateDateAndTime(request.date(), time, LocalDateTime.now());
        validateDuplication(request, time, theme, member);
        validateCanReserve(request, order);

        Reservation savedReservation = reservationRepository.save(request.toEntity(time, theme, member));
        memberReservationRepository.save(new MemberReservation(savedReservation, member, request.status(), order));
        return ReservationResponse.from(savedReservation);
    }

    private void validateCanReserve(final ReservationRequest request, final long order) {
        if (request.status().isReserved() && order > 0) {
            throw new ValidateException(ErrorType.INVALID_REQUEST_DATA, "이미 요청하신 날짜/테마/시간 에 예약 정보가 존재하여 예약할 수 없습니다. '예약 대기'로 요청해주세요.");
        }
    }

    private void validateDuplication(final ReservationRequest request, final ReservationTime requestTime, final Theme theme, final Member member) {
        if (request.status() == ReservationStatus.RESERVED) {
            validateReservationDuplicate(request.date(), requestTime, theme);
        } else if (request.status() == ReservationStatus.WAITING) {
            validateMemberReservationDuplicate(member, request.date(), requestTime, theme);
        }
    }

    private void validateDateAndTime(final LocalDate requestDate, final ReservationTime requestReservationTime, final LocalDateTime now) {
        if (isReservationInPast(requestDate, requestReservationTime, now)) {
            throw new ValidateException(ErrorType.RESERVATION_PERIOD_IN_PAST,
                    String.format("지난 날짜나 시간은 예약이 불가능합니다. [now: %s %s | request: %s %s]",
                            now.toLocalDate(), now.toLocalTime(), requestDate, requestReservationTime.getStartAt()));
        }
    }

    private boolean isReservationInPast(final LocalDate requestDate, final ReservationTime requestReservationTime, final LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();

        if (requestDate.isBefore(today)) {
            return true;
        }
        if (requestDate.isEqual(today) && requestReservationTime.isBefore(nowTime)) {
            return true;
        }
        return false;
    }

    private void validateReservationDuplicate(final LocalDate requestDate, final ReservationTime time, final Theme theme) {
        Optional<Reservation> reservation = reservationRepository.findByReservationTimeAndDateAndTheme(
                time, requestDate, theme);

        if (reservation.isPresent()) {
            throw new DataDuplicateException(ErrorType.RESERVATION_WAITING_DUPLICATED,
                    String.format("이미 해당 날짜/시간/테마에 예약이 존재합니다. [values: %s/%s/%s]", requestDate, time, theme));
        }
    }

    private void validateMemberReservationDuplicate(final Member member, final LocalDate requestDate, final ReservationTime time, final Theme theme) {
        Optional<MemberReservation> memberReservation = memberReservationRepository.findByMemberAndReservationTimeAndDateAndTheme(
                member, time, requestDate, theme);

        if (memberReservation.isPresent()) {
            throw new DataDuplicateException(ErrorType.RESERVATION_DUPLICATED,
                    String.format("이미 해당 날짜/시간/테마에 예약 또는 예약대기 중 입니다. [values: %s/%s/%s]", requestDate, time, theme));
        }
    }

    public ReservationsResponse searchWith(
            final Long themeId, final Long memberId, final LocalDate dateFrom, final LocalDate dateTo) {
        Member member = memberService.findMemberById(memberId);
        Theme theme = themeService.findThemeById(themeId);

        List<ReservationResponse> response = reservationRepository.searchWith(theme, member, dateFrom, dateTo).stream()
                .map(ReservationResponse::from)
                .toList();
        return new ReservationsResponse(response);
    }

    // TODO: 코드 메서드 분리, 날짜, 시간 순서로 정렬
    public MemberReservationsResponse findReservationByMemberId(final Long memberId) {
        Member member = memberService.findMemberById(memberId);
        List<MemberReservation> reservations = memberReservationRepository.findByMember(member);

        List<MemberReservationResponse> responses = new ArrayList<>();
        for (MemberReservation memberReservation : reservations) {
            if (!memberReservation.isReserved()) {
                responses.add(MemberReservationResponse.fromEntity(memberReservation));
                continue;
            }
            responses.add(MemberReservationResponse.fromEntity(memberReservation));
        }
        return new MemberReservationsResponse(responses);
    }
}
