package ca.bc.gov.educ.api.penmatch.service;

import ca.bc.gov.educ.api.penmatch.constants.PenStatus;
import ca.bc.gov.educ.api.penmatch.lookup.PenMatchLookupManager;
import ca.bc.gov.educ.api.penmatch.model.StudentEntity;
import ca.bc.gov.educ.api.penmatch.struct.v1.*;
import ca.bc.gov.educ.api.penmatch.struct.v1.newmatch.NewPenMatchRecord;
import ca.bc.gov.educ.api.penmatch.struct.v1.newmatch.NewPenMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.v1.newmatch.NewPenMatchSession;
import ca.bc.gov.educ.api.penmatch.struct.v1.newmatch.NewPenMatchStudentDetail;
import ca.bc.gov.educ.api.penmatch.util.JsonUtil;
import ca.bc.gov.educ.api.penmatch.util.PenMatchUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@Service
@Slf4j
public class NewPenMatchService {

    public static final int NOT_VERY_FREQUENT = 50;
    public static final int VERY_FREQUENT = 500;
    public static final int MIN_SURNAME_SEARCH_SIZE = 4;
    public static final int MAX_SURNAME_SEARCH_SIZE = 6;
    public static final int MIN_SURNAME_COMPARE_SIZE = 5;
    private boolean reOrganizedNames = false;
    private HashSet<String> oneMatchOverrideMainCodes;
    private HashSet<String> oneMatchOverrideSecondaryCodes;

    @Autowired
    private PenMatchLookupManager lookupManager;

    public NewPenMatchService(PenMatchLookupManager lookupManager) {
        this.lookupManager = lookupManager;
        oneMatchOverrideMainCodes = new HashSet<String>();
        oneMatchOverrideMainCodes.add("1111122");
        oneMatchOverrideMainCodes.add("1111212");
        oneMatchOverrideMainCodes.add("1111221");
        oneMatchOverrideMainCodes.add("1112112");
        oneMatchOverrideMainCodes.add("1112211");
        oneMatchOverrideMainCodes.add("1121112");
        oneMatchOverrideMainCodes.add("1122111");
        oneMatchOverrideMainCodes.add("1131121");
        oneMatchOverrideMainCodes.add("1131122");
        oneMatchOverrideMainCodes.add("1131221");
        oneMatchOverrideMainCodes.add("1132111");
        oneMatchOverrideMainCodes.add("1132112");
        oneMatchOverrideMainCodes.add("1141122");
        oneMatchOverrideMainCodes.add("1141212");
        oneMatchOverrideMainCodes.add("1141221");
        oneMatchOverrideMainCodes.add("1211111");
        oneMatchOverrideMainCodes.add("1211112");
        oneMatchOverrideMainCodes.add("1231111");
        oneMatchOverrideMainCodes.add("1231211");
        oneMatchOverrideMainCodes.add("1241111");
        oneMatchOverrideMainCodes.add("1241112");
        oneMatchOverrideMainCodes.add("1241211");
        oneMatchOverrideMainCodes.add("1321111");
        oneMatchOverrideMainCodes.add("2111111");
        oneMatchOverrideMainCodes.add("2111112");
        oneMatchOverrideMainCodes.add("2111121");
        oneMatchOverrideMainCodes.add("2111211");
        oneMatchOverrideMainCodes.add("2112111");
        oneMatchOverrideMainCodes.add("2131111");
        oneMatchOverrideMainCodes.add("2131121");
        oneMatchOverrideMainCodes.add("2131211");
        oneMatchOverrideMainCodes.add("2132111");
        oneMatchOverrideMainCodes.add("2141111");
        oneMatchOverrideMainCodes.add("2141112");
        oneMatchOverrideMainCodes.add("2141211");
        oneMatchOverrideMainCodes.add("2142111");

        oneMatchOverrideSecondaryCodes = new HashSet<String>();
        oneMatchOverrideSecondaryCodes.add("1131221");
        oneMatchOverrideSecondaryCodes.add("1211111");
        oneMatchOverrideSecondaryCodes.add("1211112");
        oneMatchOverrideSecondaryCodes.add("1231111");
        oneMatchOverrideSecondaryCodes.add("1321111");
        oneMatchOverrideSecondaryCodes.add("2131111");
        oneMatchOverrideSecondaryCodes.add("2132111");
    }

    /**
     * This is the main method to match a student
     */
    //Complete
    public NewPenMatchResult matchStudent(NewPenMatchStudentDetail student) {
        log.info(" input :: PenMatchStudentDetail={}", JsonUtil.getJsonPrettyStringFromObject(student));
        NewPenMatchSession session = initialize(student);

        PenConfirmationResult confirmationResult = new PenConfirmationResult();
        confirmationResult.setDeceased(false);

        if (student.getPen() != null) {
            boolean validCheckDigit = PenMatchUtils.penCheckDigit(student.getPen());
            if (validCheckDigit) {
                // Attempt to confirm a supplied PEN
                confirmationResult = confirmPEN(student, session);
                if (confirmationResult.getPenConfirmationResultCode().equals(PenConfirmationResult.PEN_CONFIRMED)) {
                    if (student.getStudentTrueNumber() == null) {
                        session.setPenStatus(PenStatus.AA.getValue());
                    } else {
                        session.setPenStatus(PenStatus.B1.getValue());
                    }
                } else {
                    // Find match using demographics if
                    // The supplied PEN was not confirmed or no PEN was supplied
                    findMatchesByDemog(student, session);
                    if (session.getNumberOfMatches() == 1) {
                        NewPenMatchRecord matchRecord = session.getMatchingRecords().peek();
                        if (matchRecord.getMatchResult().equals("P")) {
                            if (student.getPen() != null && student.getPen().equals(matchRecord.getMatchingPEN())) {
                                //PEN confirmed
                                session.setPenStatus(PenStatus.AA.getValue());
                            } else if (student.getPen() == null) {
                                //No PEN Supplied
                                session.setPenStatus(PenStatus.D1.getValue());
                            } else if (confirmationResult.getPenConfirmationResultCode().equals(PenConfirmationResult.PEN_ON_FILE)) {
                                //Wrong PEN Supplied
                                session.setPenStatus(PenStatus.B1.getValue());
                            } else {
                                //Invalid PEN Supplied
                                session.setPenStatus(PenStatus.C1.getValue());
                            }
                        } else {
                            if (matchRecord.getMatchResult() == null) {
                                //Unknown match result
                                session.setPenStatus(PenStatus.UR.getValue());
                            } else {
                                //Single questionable match
                                session.setPenStatus(PenStatus.F1.getValue());
                                if (matchRecord.getMatchingPEN().equals("Old F1")) {
                                    session.setBestMatchPEN(null);
                                    session.setBestMatchCode(null);
                                } else {
                                    session.setBestMatchPEN(matchRecord.getMatchingPEN());
                                    session.setBestMatchCode(matchRecord.getMatchCode());
                                }
                            }
                        }
                    } else if (session.getNumberOfMatches() > 1) {
                        if (student.getPen() == null) {
                            //No PEN Supplied
                            session.setPenStatus(PenStatus.DM.getValue());
                        } else if (confirmationResult.getPenConfirmationResultCode().equals(PenConfirmationResult.PEN_ON_FILE)) {
                            //Wrong PEN Supplied
                            session.setPenStatus(PenStatus.BM.getValue());
                        } else {
                            //Invalid PEN Supplied
                            session.setPenStatus(PenStatus.CM.getValue());
                        }
                        determineBestMatch();
                    } else {
                        //! Assign a new PEN if there were no matches and the flag was passed to assign
                        //! new PENS (not just lookup mode) (NO LONGER DONE HERE - NEW PENS NOW ASSIGNED
                        //! IN CALLING QUICK PROGRAM VIA ASSIGN_NEW_PEN.USE)
                        if (student.getPen() == null) {
                            //No PEN Supplied
                            session.setPenStatus(PenStatus.D0.getValue());
                        } else if (confirmationResult.getPenConfirmationResultCode().equals(PenConfirmationResult.PEN_ON_FILE)) {
                            //Wrong PEN Supplied
                            session.setPenStatus(PenStatus.B0.getValue());
                        } else {
                            //Invalid PEN Supplied
                            session.setPenStatus(PenStatus.C0.getValue());
                        }

                        if (session.isAssignNewPEN() && session.getPenStatus().equals(PenStatus.B0.getValue())) {
                            if (student.getSurname() == null || student.getGivenName() == null || student.getDob() == null || student.getSex() == null || student.getMincode() == null) {
                                session.setPenStatus(PenStatus.G0.getValue());
                            }
                        }
                    }
                }
            }

        }

        NewPenMatchResult result = new NewPenMatchResult(session.getMatchingRecords(), session.getStudentNumber(), session.getPenStatus(), session.getPenStatusMessage());
        log.info(" output :: NewPenMatchResult={}", JsonUtil.getJsonPrettyStringFromObject(result));
        return result;
    }

    /**
     * Find all possible students on master who could match the transaction.
     * If the first four characters of surname are uncommon then only use 4
     * characters in lookup.  Otherwise use 6 characters, or 5 if surname is
     * only 5 characters long
     * use the given initial in the lookup unless 1st 4 characters of surname is
     * quite rare
     */
    private void findMatchesByDemog(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        boolean useGiven = true;

        if (student.getPartialSurnameFrequency() <= NOT_VERY_FREQUENT) {
            student.setPartialStudentSurname(student.getSurname().substring(0, student.getMinSurnameSearchSize()));
            useGiven = false;
        } else if (student.getPartialSurnameFrequency() <= VERY_FREQUENT) {
            student.setPartialStudentSurname(student.getSurname().substring(0, student.getMinSurnameSearchSize()));
            student.setPartialStudentGiven(student.getGivenName().substring(0, 1));
        } else {
            student.setPartialStudentSurname(student.getSurname().substring(0, student.getMaxSurnameSearchSize()));
            student.setPartialStudentGiven(student.getGivenName().substring(0, 2));
        }

        if (useGiven) {
            lookupByDobSurnameGiven(student, session);
            lookupByDobSurname(student, session);
        }

        //Post-match overrides
        if (session.getNumberOfMatches() == 1 && session.getApplicationCode().equals("SLD")) {
            oneMatchOverrides(student, session);
        }

        if (session.getNumberOfMatches() > 0 && session.getApplicationCode().equals("SLD")) {
            changeResultFromQtoF();
        }

        appendOldF1(student, session);
    }

    //!---------------------------------------------------------------------------
    //! Read Pen master by BIRTH DATE or SURNAME or (MINCODE and LOCAL ID)
    //!---------------------------------------------------------------------------
    //Complete
    private void lookupByDobSurnameGiven(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        List<StudentEntity> penDemogList = lookupManager.lookupNoLocalID(student.getDob(), student.getPartialStudentSurname(), student.getPartialStudentGiven());
        for (StudentEntity entity : penDemogList) {
            determineIfMatch(student, PenMatchUtils.convertStudentEntityToPenMasterRecord(entity), session);
        }
    }

    //!---------------------------------------------------------------------------
    //! Determine if the match is a Pass or Fail
    //!---------------------------------------------------------------------------
    //Complete
    private void determineIfMatch(NewPenMatchStudentDetail student, PenMasterRecord masterRecord, NewPenMatchSession session) {
        String matchCode = determineMatchCode(student, masterRecord);

        //Lookup Result
        String matchResult = lookupManager.lookupMatchResult(matchCode);

        if (matchResult == null) {
            matchResult = "F";
        }

        //Apply overrides to Questionable Match
        if (matchResult.equals("Q") && session.getApplicationCode().equals("SLD")) {
            matchOverrides();
        }

        //Store PEN, match code and result in table (except if Fail)
        if (!matchResult.equals("F") && session.getNumberOfMatches() < 20) {
            if (!masterRecord.getStatus().equals("D")) {
                session.setNumberOfMatches(session.getNumberOfMatches() + 1);
                session.getMatchingRecords().add(new NewPenMatchRecord(masterRecord.getPen(), matchCode, matchResult));
            } else {
                session.setPenStatus(PenStatus.C0.getValue());
            }
        }
    }

    //!---------------------------------------------------------------------------
    //! Read Pen master by BIRTH DATE or (SURNAME AND GIVEN NAME)
    //!                               or (MINCODE and LOCAL ID)
    //!---------------------------------------------------------------------------
    private void lookupByDobSurname(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        List<StudentEntity> penDemogList = lookupManager.lookupNoInitNoLocalID(student.getDob(), student.getPartialStudentSurname());
        for (StudentEntity entity : penDemogList) {
            determineIfMatch(student, PenMatchUtils.convertStudentEntityToPenMasterRecord(entity), session);
        }
    }

    //!---------------------------------------------------------------------------
    //! Override: Change result if there is one match and it meets specific
    //! criteria for specific match codes
    //!---------------------------------------------------------------------------
    private void oneMatchOverrides(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        //! 1 match and matched PEN is F1 PEN from the Old PEN Match
        NewPenMatchRecord matchRecord = session.getMatchingRecords().peek();
        if (matchRecord.getMatchResult().equals("Q")) {
            if (student.getOldMatchF1PEN() != null && matchRecord.getMatchingPEN().equals(student.getOldMatchF1PEN())) {
                if (oneMatchOverrideMainCodes.contains(matchRecord.getMatchCode())) {
                    matchRecord.setMatchResult("P");
                }
                if (matchRecord.getMatchCode().equals("1221111") && !session.isPSI()) {
                    matchRecord.setMatchResult("P");
                }
            } else if (matchRecord.getMatchingPEN().equals(student.getPen()) && oneMatchOverrideSecondaryCodes.contains(matchRecord.getMatchCode())) {
                //! 1 match and matched PEN is the School supplied PEN
                matchRecord.setMatchCode("P");
            }
        }
    }

    /**
     * Initialize the student record and variables (will be refactored)
     */
    //Complete
    private NewPenMatchSession initialize(NewPenMatchStudentDetail student) {
        log.info(" input :: NewPenMatchStudentDetail={}", JsonUtil.getJsonPrettyStringFromObject(student));
        NewPenMatchSession session = new NewPenMatchSession();

        if (student.getMincode() != null && student.getMincode().length() >= 3 && student.getMincode().startsWith("102")) {
            session.setPSI(true);
        }

        student.setPenMatchTransactionNames(formatNamesFromTransaction(student));

        // Lookup surname frequency
        // It could generate extra points later if
        // there is a perfect match on surname
        int partialSurnameFrequency;
        String fullStudentSurname = student.getSurname();
        int fullSurnameFrequency = lookupManager.lookupSurnameFrequency(fullStudentSurname);

        if (fullSurnameFrequency > VERY_FREQUENT) {
            partialSurnameFrequency = fullSurnameFrequency;
        } else {
            fullStudentSurname = student.getSurname().substring(0, student.getMinSurnameSearchSize());
            partialSurnameFrequency = lookupManager.lookupSurnameFrequency(fullStudentSurname);
        }

        student.setFullSurnameFrequency(fullSurnameFrequency);
        student.setPartialSurnameFrequency(partialSurnameFrequency);

        log.info(" output :: NewPenMatchSession={}", JsonUtil.getJsonPrettyStringFromObject(session));
        return session;
    }

    /**
     * This function stores all names in an object
     */
    //Complete
    private PenMatchNames formatNamesFromTransaction(NewPenMatchStudentDetail student) {
        log.info(" input :: NewPenMatchStudentDetail={}", JsonUtil.getJsonPrettyStringFromObject(student));
        String surname = student.getSurname();
        String usualSurname = student.getUsualSurname();
        String given = student.getGivenName();
        String usualGiven = student.getUsualGivenName();
        PenMatchNames penMatchTransactionNames;

        penMatchTransactionNames = new PenMatchNames();
        penMatchTransactionNames.setLegalSurname(PenMatchUtils.dropNonLetters(surname));
        penMatchTransactionNames.setLegalGiven(PenMatchUtils.dropNonLetters(given));
        penMatchTransactionNames.setLegalMiddle(PenMatchUtils.dropNonLetters(student.getMiddleName()));
        penMatchTransactionNames.setUsualSurname(PenMatchUtils.dropNonLetters(usualSurname));
        penMatchTransactionNames.setUsualGiven(PenMatchUtils.dropNonLetters(usualGiven));
        return penMatchTransactionNames;
    }

    /**
     * This function stores all names in an object It includes some split logic for
     * given/middle names
     */
    //Complete
    public static PenMatchNames formatNamesFromMaster(PenMasterRecord master) {
        log.info(" input :: PenMasterRecord={}", JsonUtil.getJsonPrettyStringFromObject(master));
        String surname = master.getSurname();
        String usualSurname = master.getUsualSurname();
        String given = master.getGiven();
        String usualGiven = master.getUsualGivenName();
        PenMatchNames penMatchTransactionNames;

        penMatchTransactionNames = new PenMatchNames();
        penMatchTransactionNames.setLegalSurname(PenMatchUtils.dropNonLetters(surname));
        penMatchTransactionNames.setLegalGiven(PenMatchUtils.dropNonLetters(given));
        penMatchTransactionNames.setLegalMiddle(PenMatchUtils.dropNonLetters(master.getMiddle()));
        penMatchTransactionNames.setUsualSurname(PenMatchUtils.dropNonLetters(usualSurname));
        penMatchTransactionNames.setUsualGiven(PenMatchUtils.dropNonLetters(usualGiven));
        return penMatchTransactionNames;
    }

    /**
     * Confirm that the PEN on transaction is correct.
     */
    //Complete
    private PenConfirmationResult confirmPEN(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        log.info(" input :: NewPenMatchStudentDetail={} NewPenMatchSession={}", JsonUtil.getJsonPrettyStringFromObject(student), JsonUtil.getJsonPrettyStringFromObject(session));
        PenConfirmationResult result = new PenConfirmationResult();
        result.setPenConfirmationResultCode(PenConfirmationResult.NO_RESULT);

        String localStudentNumber = student.getPen();
        result.setDeceased(false);

        PenMasterRecord masterRecord = lookupManager.lookupStudentByPEN(localStudentNumber);

        String studentTrueNumber = null;

        if (masterRecord != null && masterRecord.getStatus() != null && masterRecord.getStatus().equals("M")) {
            studentTrueNumber = lookupManager.lookupStudentTruePENNumberByStudentID(masterRecord.getStudentID());
        }

        if (masterRecord != null && masterRecord.getPen().trim().equals(localStudentNumber)) {
            if (masterRecord.getStatus() != null && masterRecord.getStatus().equals("M") && studentTrueNumber != null) {
                student.setStudentTrueNumber(studentTrueNumber.trim());
                result.setMergedPEN(studentTrueNumber.trim());
                masterRecord = lookupManager.lookupStudentByPEN(studentTrueNumber);
                if (masterRecord != null && masterRecord.getPen().trim().equals(studentTrueNumber)) {
                    result.setPenConfirmationResultCode(PenConfirmationResult.PEN_ON_FILE);
                }
            } else {
                result.setPenConfirmationResultCode(PenConfirmationResult.PEN_ON_FILE);
            }
        }

        if (result.getPenConfirmationResultCode().equals(PenConfirmationResult.PEN_ON_FILE)) {
            String matchCode = determineMatchCode(student, masterRecord);

            String matchResult = lookupManager.lookupMatchResult(matchCode);

            if (matchResult == null) {
                matchResult = "F";
            }

            if (matchResult.equals("P")) {
                result.setPenConfirmationResultCode(PenConfirmationResult.PEN_CONFIRMED);
                session.setNumberOfMatches(1);
                session.getMatchingRecords().add(new NewPenMatchRecord(matchResult, matchCode, masterRecord.getPen().trim()));
            }
        }

        log.info(" output :: PenConfirmationResult={} NewPenMatchSession={}", JsonUtil.getJsonPrettyStringFromObject(result), JsonUtil.getJsonPrettyStringFromObject(session));
        return result;
    }

    /**
     * ---------------------------------------------------------------------------
     * Determine match code based on legal names, birth date and gender
     * ---------------------------------------------------------------------------
     */
    //Complete
    private String determineMatchCode(NewPenMatchStudentDetail student, PenMasterRecord masterRecord) {
        PenMatchNames masterNames = formatNamesFromMaster(masterRecord);

        // ! Match surname
        // ! -------------
        // !
        // ! Possible Values for SURNAME_MATCH_CODE:
        // !       1       Identical, Matches usual or partial (plus overrides to value 2)
        // !       2       Different

        String surnameMatchCode;
        String legalSurname = student.getSurname();
        String usualSurnameNoBlanks = student.getPenMatchTransactionNames().getUsualSurname();
        String legalSurnameNoBlanks = student.getPenMatchTransactionNames().getLegalSurname();
        String legalSurnameHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(student.getPenMatchTransactionNames().getLegalSurname());
        String masterLegalSurnameNoBlanks = masterNames.getLegalSurname();
        String masterUsualSurnameNoBlanks = masterNames.getUsualSurname();
        String masterLegalSurnameHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(masterNames.getLegalSurname());

        // !   submitted legal surname missing (shouldn't happen)
        if (legalSurname == null) {
            surnameMatchCode = "2";
        } else if (masterLegalSurnameNoBlanks != null && masterLegalSurnameNoBlanks.equals(legalSurnameNoBlanks)) {
            // !   submitted legal surname equals master legal surname
            surnameMatchCode = "1";
        } else {
            // !   submitted legal surname is part of master legal surname or vice verse
            String transactionName = " " + legalSurnameHyphenToSpace + " ";
            String masterName = " " + masterLegalSurnameHyphenToSpace + " ";
            if (PenMatchUtils.checkForPartialName(transactionName, masterName)) {
                surnameMatchCode = "1";
            } else {
                surnameMatchCode = "2";
            }
        }

        //!   Overrides: above resulted in match code 2 and
        //!   (submitted legal surname equals master usual surname or
        //!    submitted usual surname equals master legal surname)
        if (surnameMatchCode.equals("2") && (legalSurnameNoBlanks != null && legalSurnameNoBlanks.equals(masterUsualSurnameNoBlanks)) || (usualSurnameNoBlanks != null && usualSurnameNoBlanks.equals(masterLegalSurnameNoBlanks))) {
            surnameMatchCode = "1";
        }

        // ! Match given name
        //! ----------------
        //!
        //! Possible Values for GIVEN_MATCH_CODE:
        //!       1       Identical, nickname or partial (plus overrides to value 2)
        //!       2       Different
        //!       3       Same initial
        //
        //!   submitted legal given name missing (shouldn't happen)
        String givenNameMatchCode;
        String legalGiven = student.getGivenName();
        String legalGivenNoBlanks = student.getPenMatchTransactionNames().getLegalGiven();
        String usualGivenNoBlanks = student.getPenMatchTransactionNames().getUsualGiven();
        String legalGivenHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(student.getPenMatchTransactionNames().getLegalGiven());
        String masterLegalGivenName = masterRecord.getGiven().trim();
        String masterLegalGivenNameNoBlanks = masterNames.getLegalGiven();
        String masterUsualGivenNameNoBlanks = masterNames.getUsualGiven();
        String masterLegalGivenNameHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(masterNames.getLegalGiven());

        if (legalGiven == null) {
            givenNameMatchCode = "2";
        } else if (masterLegalGivenNameNoBlanks != null && masterLegalGivenNameNoBlanks.equals(legalGivenNoBlanks)) {
            // !   submitted legal given name equals master legal given name
            givenNameMatchCode = "1";
        } else if ((legalGiven.length() >= 1 && masterLegalGivenName != null && masterLegalGivenName.length() >= 1 && legalGiven.substring(0, 1).equals(masterLegalGivenName.substring(0, 1))) && (masterLegalGivenName.length() == 1 || legalGiven.length() == 1)) {
            // !   submitted legal given name starts with the same letter as master legal given
            // !   name and one of the names has only an initial
            givenNameMatchCode = "3";
        } else {
            // !   submitted legal given name is part of master legal given name or vice verse
            String transactionName = " " + legalGivenHyphenToSpace + " ";
            String masterName = " " + masterLegalGivenNameHyphenToSpace + " ";
            if (PenMatchUtils.checkForPartialName(transactionName, masterName) && !reOrganizedNames) {
                givenNameMatchCode = "1";
            } else {
                // !   submitted legal given name is a nickname of master legal given name or vice
                // !   verse
                transactionName = legalGivenHyphenToSpace;
                masterName = masterLegalGivenNameHyphenToSpace;

                lookupManager.lookupNicknames(student.getPenMatchTransactionNames(), transactionName);

                if (student.getPenMatchTransactionNames().getNickname1() != null) {
                    givenNameMatchCode = "1";
                } else {
                    givenNameMatchCode = "2";
                }
            }
        }

        // !  Overrides: above resulted in surname match code 1 and given name match code 2
        // !  and (submitted legal given name equals master usual given name or
        // !       submitted usual given name equals master legal given name)
        if (surnameMatchCode.equals("1") && givenNameMatchCode.equals("2")) {
            if ((legalGivenNoBlanks != null && legalGivenNoBlanks.equals(masterUsualGivenNameNoBlanks)) || (usualGivenNoBlanks != null && usualGivenNoBlanks.equals(masterLegalGivenNameNoBlanks))) {
                givenNameMatchCode = "1";
            }
        }

        //! Match middle name
        //! -----------------
        //!
        //! Possible Values for MIDDLE_MATCH_CODE:
        //!       1       Identical, nickname or partial
        //!       2       Different
        //!       3       Same initial, one letter typo or one missing
        //!       4       Both missing
        String middleNameMatchCode;
        String legalMiddle = student.getMiddleName();
        String legalMiddleNoBlanks = student.getPenMatchTransactionNames().getLegalMiddle();
        String usualMiddleNoBlanks = student.getPenMatchTransactionNames().getUsualMiddle();
        String legalMiddleHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(student.getPenMatchTransactionNames().getLegalMiddle());
        String masterLegalMiddleName = masterRecord.getMiddle().trim();
        String masterLegalMiddleNameNoBlanks = masterNames.getLegalMiddle();
        String masterUsualMiddleNameNoBlanks = masterNames.getUsualMiddle();
        String masterLegalMiddleNameHyphenToSpace = PenMatchUtils.replaceHyphensWithBlank(masterNames.getLegalMiddle());

        // !   submitted legal middle name and master legal middle name are both blank
        if (legalMiddle == null && masterRecord.getMiddle() == null) {
            middleNameMatchCode = "4";
        } else if (legalMiddle == null || masterRecord.getMiddle() == null) {
            // !   submitted legal middle name or master legal middle is blank (not both)
            middleNameMatchCode = "3";
        } else if (legalMiddleNoBlanks != null && legalMiddleNoBlanks.equals(masterLegalMiddleNameNoBlanks)) {
            // !   submitted legal middle name equals master legal middle name
            middleNameMatchCode = "1";
        } else if ((legalMiddle != null && legalMiddle.length() >= 1 && masterLegalMiddleName != null && masterLegalMiddleName.length() >= 1 && legalMiddle.substring(0, 1).equals(masterLegalMiddleName.substring(0, 1))) && (masterLegalMiddleName.length() == 1 || legalMiddle.length() == 1)) {
            //!   submitted legal middle name starts with the same letter as master legal
            //!   middle name and one of the names has only an initial
            middleNameMatchCode = "3";
        } else {
            // !   submitted legal Middle name is part of master legal Middle name or vice verse
            String transactionName = " " + legalMiddleHyphenToSpace + " ";
            String masterName = " " + masterLegalMiddleNameHyphenToSpace + " ";
            if (PenMatchUtils.checkForPartialName(transactionName, masterName) && !reOrganizedNames) {
                middleNameMatchCode = "1";
            } else {
                // !   submitted legal Middle name is a nickname of master legal Middle name or vice
                // !   verse
                transactionName = legalMiddleHyphenToSpace;
                masterName = masterLegalMiddleNameHyphenToSpace;

                lookupManager.lookupNicknames(student.getPenMatchTransactionNames(), transactionName);

                if (student.getPenMatchTransactionNames().getNickname1() != null) {
                    middleNameMatchCode = "1";
                } else {
                    middleNameMatchCode = "2";
                }
            }
        }

        //! Match birth date
        //! ----------------
        //!
        //! Possible Values for YEAR_MATCH_CODE, MONTH_MATCH_CODE or DAY_MATCH_CODE:
        //!       1       Identical (plus overrides to value 2)
        //!       2       Different
        //
        //!   submitted birth date matches master
        String studentDob = student.getDob();
        String masterDob = masterRecord.getDob();
        String yearMatchCode = null;
        String monthMatchCode = null;
        String dayMatchCode = null;

        if (studentDob != null && studentDob.equals(masterDob)) {
            // !   submitted birth date matches master
            yearMatchCode = "1";
            monthMatchCode = "1";
            dayMatchCode = "1";
        } else if (studentDob != null && studentDob.length() >= 4 && studentDob.substring(0, 4).equals(masterDob.substring(0, 1))) {
            // !   submitted year matches master
            yearMatchCode = "1";
        } else {
            yearMatchCode = "2";
        }

        // !   submitted month matches master
        if (studentDob != null && studentDob.length() >= 6 && studentDob.substring(4, 6).equals(masterDob.substring(4, 6))) {
            monthMatchCode = "1";
        } else {
            monthMatchCode = "2";
        }

        // !   submitted day matches master
        if (studentDob != null && studentDob.length() >= 8 && studentDob.substring(6, 8).equals(masterDob.substring(6, 8))) {
            dayMatchCode = "1";
        } else {
            dayMatchCode = "2";
        }

        String birthdayMatchCode = yearMatchCode + monthMatchCode + dayMatchCode;

        //!   Override:
        //!   only submitted year didn't match master but the last 2 digits are transposed
        if (birthdayMatchCode.equals("211")) {
            String tempDobYear = studentDob.substring(3, 4) + studentDob.substring(2, 3);
            if (tempDobYear.equals(masterDob.substring(2, 4))) {
                yearMatchCode = "1";
            }
        } else if (birthdayMatchCode.equals("121")) {
            // !   Override:
            // !   only submitted month didn't match master but the last 2 digits are transposed
            String tempDobMonth = studentDob.substring(5, 6) + studentDob.substring(4, 5);
            if (tempDobMonth.equals(masterDob.substring(4, 6))) {
                monthMatchCode = "1";
            }
        } else if (birthdayMatchCode.equals("112")) {
            // !   Override:
            // !   only submitted day didn't match master but the last 2 digits are transposed
            String tempDobDay = studentDob.substring(7, 8) + studentDob.substring(6, 7);
            if (tempDobDay.equals(masterDob.substring(6, 8))) {
                dayMatchCode = "1";
            }
        } else if (birthdayMatchCode.equals("122") && studentDob.substring(4, 6).equals(masterDob.substring(6, 8)) && studentDob.substring(6, 8).equals(masterDob.substring(4, 6))) {
            // !   Override:
            // !   Year matched master but month and day did not and they are transposed
            monthMatchCode = "1";
            dayMatchCode = "1";
        }

        // ! Match gender
        // ! ------------
        // !
        // ! Possible Values for GENDER_MATCH_CODE:
        // !       1       Identical
        // !       2       Different
        String genderMatchCode;
        String studentSex = student.getSex();
        String masterSex = masterRecord.getSex();

        if (studentSex != null && studentSex.equals(masterSex)) {
            genderMatchCode = "1";
        } else {
            genderMatchCode = "2";
        }

        return surnameMatchCode + givenNameMatchCode + middleNameMatchCode + yearMatchCode + monthMatchCode + dayMatchCode + genderMatchCode;
    }


    //!---------------------------------------------------------------------------
    //! Overrides that apply immediately after a Match Code is calculated.
    //!---------------------------------------------------------------------------
    private void matchOverrides() {
        //TODO
    }

    //!---------------------------------------------------------------------------
    //! Override: Change result from Q to F for specific match codes if the
    //! transaction meets specific criteria and drop the fails from the list (array)
    //!---------------------------------------------------------------------------
    private void changeResultFromQtoF() {
        //TODO
    }

    //!---------------------------------------------------------------------------
    //! Override: Check the list of matches for the F1 PEN from the Old PEN Match
    //! and add it to the list if it is not already there. Replace the last match
    //! in the list with the F1 PEN if the list is full (20 matches). Set the
    //! result of the added match to 'Questionable'.
    //!---------------------------------------------------------------------------
    private void appendOldF1(NewPenMatchStudentDetail student, NewPenMatchSession session) {
        boolean penF1Found;
        if(student.getOldMatchF1PEN() != null){
            penF1Found = false;
            if(session.getNumberOfMatches() > 0){
                NewPenMatchRecord [] matchRecords = session.getMatchingRecords().toArray(new NewPenMatchRecord[session.getMatchingRecords().size()]);
                for(NewPenMatchRecord record: matchRecords){
                    if(record.getMatchingPEN().equals(student.getOldMatchF1PEN())){
                        penF1Found = true;
                    }
                }
            }
        }
    }

    /**
     * !---------------------------------------------------------------------------
     * ! Determine the 'Best Match' when there are multiple matched.
     * !
     * ! The rules for determining the 'Best Match" are:
     * ! Sum the 7 positions of Match Code. The 'Best Match' has the lowest value.
     * ! If there are ties:
     * ! The Match Code with the most ones is the 'Best Match'.
     * ! If there are ties:
     * ! The 'Match Code' with the most twos is the 'Best Match.
     * ! If there are ties:
     * ! The 'Match Code' with the most threes is the 'Best Match.
     * ! If there are ties:
     * ! The lowest Match Code value is the 'Best Match'.
     * !
     * ! The easiest way to select the 'Best Match' is to put the result of all of
     * ! the above calculations into one comparable value. To do this we need to
     * ! convert the number of ones, twos and threes into their inverted values by
     * ! by subtracting each from 7 (the maximum). Now the 'Best Match' will
     * ! have the lowest value from all of the above calculations allowing us to
     * ! concatenate the results and select the Match Code with the lowest concatenated
     * ! value. This allows us to loop through all found Match Codes calculating the
     * ! concatenated value and saving it and the applicable Match Code/PEN whenever
     * ! the concatenated value is less than the previously saved value.
     * !---------------------------------------------------------------------------
     */
    private void determineBestMatch() {

    }
}
