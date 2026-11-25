package com.seatrush.service;

import com.seatrush.dto.DropResponse;
import com.seatrush.dto.MyRegistrationResponse;
import com.seatrush.dto.RegistrationResponse;
import com.seatrush.entity.Registration;
import com.seatrush.entity.RegistrationStatus;
import com.seatrush.entity.Section;
import com.seatrush.exception.AppException;
import com.seatrush.repository.RegistrationRepository;
import com.seatrush.repository.SectionRepository;
import com.seatrush.repository.StudentRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final SectionRepository sectionRepository;
    private final RegistrationRepository registrationRepository;
    private final StudentRepository studentRepository;
    private final EntityManager entityManager;

    // Invariants:
    // - seats_taken never exceeds capacity
    // - one registration row per (student, section)
    // - all booking writes go through BookingService
    // - takeSeat first so the exclusive row lock is acquired before the FK check's shared lock;
    //   taking it last causes S->X lock upgrade deadlocks under concurrency.
    @Transactional
    public RegistrationResponse register(Long studentId, Long sectionId) {
        // 1. Load section (plain read); unknown id -> 404 NOT_FOUND.
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Section not found: " + sectionId, HttpStatus.NOT_FOUND));

        // 2. Fast path: plain non-locking read of the existing registration.
        //    If CONFIRMED -> 409 ALREADY_REGISTERED immediately (no lock taken).
        Optional<Registration> existingOpt = registrationRepository.findByStudentIdAndSectionId(studentId, sectionId);
        if (existingOpt.isPresent() && existingOpt.get().getStatus() == RegistrationStatus.CONFIRMED) {
            throw new AppException("ALREADY_REGISTERED", "Already registered for section " + sectionId, HttpStatus.CONFLICT);
        }

        // 3. takeSeat(sectionId) FIRST. 0 rows affected -> throw SECTION_FULL (nothing else done yet).
        int seatsTakenUpdated = sectionRepository.takeSeat(sectionId);
        if (seatsTakenUpdated == 0) {
            throw new AppException("SECTION_FULL", "Section " + sectionId + " is full", HttpStatus.CONFLICT);
        }

        // 4. Then reactivate (conditional UPDATE ... WHERE status='DROPPED') or insert the registration.
        //    Any failure here (unique violation, 0 rows on reactivate) -> let the exception propagate so the
        //    transaction rolls back and the seat from step 3 is released. Map to ALREADY_REGISTERED.
        Long registrationId;
        if (existingOpt.isPresent()) {
            Registration existing = existingOpt.get();
            if (existing.getStatus() == RegistrationStatus.DROPPED) {
                int updated = entityManager.createNativeQuery(
                        "UPDATE registrations SET status = 'CONFIRMED' WHERE id = :id AND status = 'DROPPED'")
                        .setParameter("id", existing.getId())
                        .executeUpdate();
                if (updated == 0) {
                    throw new AppException("ALREADY_REGISTERED", "Already registered for section " + sectionId, HttpStatus.CONFLICT);
                }
                existing.setStatus(RegistrationStatus.CONFIRMED);
                registrationId = existing.getId();
            } else {
                throw new AppException("ALREADY_REGISTERED", "Invalid registration state for section " + sectionId, HttpStatus.CONFLICT);
            }
        } else {
            Registration newReg = Registration.builder()
                    .student(studentRepository.getReferenceById(studentId))
                    .section(section)
                    .status(RegistrationStatus.CONFIRMED)
                    .build();
            try {
                registrationRepository.saveAndFlush(newReg);
                registrationId = newReg.getId();
            } catch (DataIntegrityViolationException e) {
                throw new AppException("ALREADY_REGISTERED", "Already registered for section " + sectionId, HttpStatus.CONFLICT);
            }
        }

        return new RegistrationResponse(registrationId, sectionId, RegistrationStatus.CONFIRMED);
    }

    @Transactional(readOnly = true)
    public List<MyRegistrationResponse> getMyRegistrations(Long studentId) {
        return entityManager.createQuery(
                "SELECT new com.seatrush.dto.MyRegistrationResponse(" +
                        "s.id, c.code, s.sectionCode, r.status) " +
                        "FROM Registration r JOIN r.section s JOIN s.course c " +
                        "WHERE r.student.id = :studentId " +
                        "ORDER BY r.createdAt DESC",
                MyRegistrationResponse.class)
                .setParameter("studentId", studentId)
                .getResultList();
    }

    // Lock order: acquires the exclusive (X) lock on the section row via releaseSeat() FIRST,
    // before touching/updating the registration row. This preserves the same lock order
    // as register() (see D10) to prevent deadlocks under concurrent register and drop operations.
    @Transactional
    public DropResponse drop(Long studentId, Long sectionId) {
        // 1. Plain read: registration for (student, section). Missing or not CONFIRMED -> 404 NOT_FOUND.
        Registration registration = registrationRepository.findByStudentIdAndSectionId(studentId, sectionId)
                .filter(r -> r.getStatus() == RegistrationStatus.CONFIRMED)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Registration not found or not active for section " + sectionId, HttpStatus.NOT_FOUND));

        // 2. releaseSeat(sectionId) FIRST (takes the section X lock). 0 rows -> throw so nothing is committed.
        int seatsReleased = sectionRepository.releaseSeat(sectionId);
        if (seatsReleased == 0) {
            throw new AppException("NOT_FOUND", "Section not found or no seats to release: " + sectionId, HttpStatus.NOT_FOUND);
        }

        // 3. Conditional update: UPDATE registrations SET status='DROPPED' WHERE id=? AND status='CONFIRMED'.
        //    0 rows affected -> throw to roll back (this releases the seat from step 2).
        int updated = entityManager.createNativeQuery(
                "UPDATE registrations SET status = 'DROPPED' WHERE id = :id AND status = 'CONFIRMED'")
                .setParameter("id", registration.getId())
                .executeUpdate();
        if (updated == 0) {
            throw new AppException("NOT_FOUND", "Registration already dropped or not active", HttpStatus.NOT_FOUND);
        }

        registration.setStatus(RegistrationStatus.DROPPED);

        return new DropResponse(sectionId, "DROPPED");
    }
}
