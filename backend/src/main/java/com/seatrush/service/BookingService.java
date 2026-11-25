package com.seatrush.service;

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
    @Transactional
    public RegistrationResponse register(Long studentId, Long sectionId) {
        // 1. Load section; unknown id -> 404 NOT_FOUND.
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Section not found: " + sectionId, HttpStatus.NOT_FOUND));

        // 2. Look up the existing registration for (student, section).
        Optional<Registration> existingOpt = registrationRepository.findByStudentIdAndSectionId(studentId, sectionId);
        Long registrationId;

        if (existingOpt.isPresent()) {
            Registration existing = existingOpt.get();
            if (existing.getStatus() == RegistrationStatus.CONFIRMED) {
                throw new AppException("ALREADY_REGISTERED", "Already registered for section " + sectionId, HttpStatus.CONFLICT);
            } else if (existing.getStatus() == RegistrationStatus.DROPPED) {
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

        // 3. LAST, call takeSeat(sectionId). 0 rows affected -> throw SECTION_FULL so the whole
        //    transaction rolls back (including the registration row from step 2).
        // Reason for the order: the takeSeat UPDATE locks the hot section row until commit,
        // so it must come last to keep that lock held for as short a time as possible.
        int seatsTakenUpdated = sectionRepository.takeSeat(sectionId);
        if (seatsTakenUpdated == 0) {
            throw new AppException("SECTION_FULL", "Section " + sectionId + " is full", HttpStatus.CONFLICT);
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
}
