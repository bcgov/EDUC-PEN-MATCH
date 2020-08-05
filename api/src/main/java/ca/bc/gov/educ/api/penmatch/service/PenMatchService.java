package ca.bc.gov.educ.api.penmatch.service;

import java.util.ArrayList;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.fujion.common.StrUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ca.bc.gov.educ.api.penmatch.enumeration.PenAlgorithm;
import ca.bc.gov.educ.api.penmatch.exception.PENMatchRuntimeException;
import ca.bc.gov.educ.api.penmatch.model.PenDemographicsEntity;
import ca.bc.gov.educ.api.penmatch.repository.PenDemographicsRepository;
import ca.bc.gov.educ.api.penmatch.struct.LocalIDMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.MiddleNameMatchResult;
import ca.bc.gov.educ.api.penmatch.struct.PenConfirmationResult;
import ca.bc.gov.educ.api.penmatch.struct.PenMasterRecord;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchNames;
import ca.bc.gov.educ.api.penmatch.struct.PenMatchStudent;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PenMatchService {

	private PenMatchNames penMatchTransactionNames;
	private PenMatchNames penMatchMasterNames;

	public static final String SOUNDEX_CHARACTERS = StringUtils.repeat(" ", 65) + "01230120022455012623010202"
			+ StringUtils.repeat(" ", 6) + "01230120022455012623010202" + StringUtils.repeat(" ", 5);
	public static final String CHECK_DIGIT_ERROR_CODE_000 = "000";
	public static final String CHECK_DIGIT_ERROR_CODE_001 = "001";
	public static final String PEN_STATUS_AA = "AA";
	public static final String PEN_STATUS_B = "B";
	public static final String PEN_STATUS_B1 = "B1";
	public static final String PEN_STATUS_C = "C";
	public static final String PEN_STATUS_C0 = "C0";
	public static final String PEN_STATUS_C1 = "C1";
	public static final String PEN_STATUS_D = "D";
	public static final String PEN_STATUS_D0 = "D0";
	public static final String PEN_STATUS_D1 = "D1";
	public static final String PEN_STATUS_F = "F";
	public static final String PEN_STATUS_F1 = "F1";
	public static final String PEN_STATUS_G0 = "G0";
	public static final Integer VERY_FREQUENT = 500;
	public static final Integer NOT_VERY_FREQUENT = 50;
	public static final Integer VERY_RARE = 5;
	private String[] matchingPENs;
	private Integer[] matchingAlgorithms;
	private Integer[] matchingScores;
	private Integer reallyGoodMatches;
	private Integer prettyGoodMatches;
	private String reallyGoodPEN;
	private boolean type5Match;
	private boolean type5F1;
	private boolean matchFound;
	private String alternateLocalID;
	private Integer minSurnameSearchSize;
	private Integer maxSurnameSearchSize;
	private Integer surnameSize;
	private Integer fullSurnameFrequency;
	private Integer partSurnameFrequency;
	private PenAlgorithm algorithmUsed;
	private Integer surnamePoints;
	private Integer givenNamePoints;
	private boolean legalUsed;
	private boolean givenFlip;
	private String updateCode;

	@Getter(AccessLevel.PRIVATE)
	private final PenDemographicsRepository penDemographicsRepository;

	@Autowired
	public PenMatchService(final PenDemographicsRepository penDemographicsRepository) {
		this.penDemographicsRepository = penDemographicsRepository;
	}

	public PenMatchStudent matchStudent(PenMatchStudent student) {
		log.info("Received student payload :: {}", student);
		boolean penFoundOnMaster = false;

		initialize(student);

		if (student.getStudentNumber() != null) {
			String checkDigitErrorCode = penCheckDigit(student.getStudentNumber());
			if (checkDigitErrorCode != null) {
				if (checkDigitErrorCode.equals(CHECK_DIGIT_ERROR_CODE_000)) {
					PenConfirmationResult penConfirmation = confirmPEN(student);
					if (penConfirmation.getResultCode() == PenConfirmationResult.PEN_CONFIRMED) {
						if (penConfirmation.getMergedPEN() == null) {
							student.setPenStatus(PEN_STATUS_AA);
							student.setStudentNumber(penConfirmation.getPenMasterRecord().getMasterStudentNumber());
						} else {
							student.setPenStatus(PEN_STATUS_B1);
							student.setStudentNumber(penConfirmation.getMergedPEN());
							student.setPen1(penConfirmation.getMergedPEN());
							student.setNumberOfMatches(1);
						}
					} else if (penConfirmation.getResultCode() == PenConfirmationResult.PEN_ON_FILE) {
						student.setPenStatus(PEN_STATUS_B);
						if (penConfirmation.getPenMasterRecord().getMasterStudentNumber() != null) {
							penFoundOnMaster = true;
						}
						findMatchesOnPenDemog(student, penFoundOnMaster, penConfirmation.getLocalStudentNumber());
					} else {
						student.setPenStatus(PEN_STATUS_C);
						findMatchesOnPenDemog(student, penFoundOnMaster, penConfirmation.getLocalStudentNumber());
					}

				} else if (checkDigitErrorCode.equals(CHECK_DIGIT_ERROR_CODE_001)) {
					student.setPenStatus(PEN_STATUS_C);
					findMatchesOnPenDemog(student, penFoundOnMaster, null);
				}
			}
		} else {
			student.setPenStatus(PEN_STATUS_D);
			findMatchesOnPenDemog(student, penFoundOnMaster, null);
		}

		/*
		 * Assign a new PEN for status C0 or D0 unless Special Match or Search
		 * t_update_code values: Y or R - Assign new PEN (NO LONGER DONE HERE - NOW
		 * ASSIGNED IN ASSIGN_NEW_PEN.USE) N - Match only, do not assign new PEN S -
		 * Search only, do not assign new PEN
		 * 
		 * NOTE: Logic will need to be inserted to check to see if the new PEN exists
		 * once PEN 222222226 is reached. That PEN number as well as PENs starting with
		 * digits 3-9 have already been assigned in an earlier version of PEN.
		 */
		if ((student.getPenStatus() == PEN_STATUS_C0 || student.getPenStatus() == PEN_STATUS_D0)
				&& (student.getUpdateCode() != null
						&& (student.getUpdateCode().equals("Y") || student.getUpdateCode().equals("R")))) {
			checkForCoreData(student);
		}

		if (student.getPenStatus() == PEN_STATUS_AA || student.getPenStatus() == PEN_STATUS_B1
				|| student.getPenStatus() == PEN_STATUS_C1 || student.getPenStatus() == PEN_STATUS_D1) {
			PenMasterRecord penMasterRecord = getPENDemogMasterRecord(student.getStudentNumber());
			if (penMasterRecord.getMasterStudentDob() != student.getDob()) {
				student.setPenStatusMessage(
						"Birthdays are suspect: " + penMasterRecord.getMasterStudentDob() + " vs " + student.getDob());
				student.setPenStatus(PEN_STATUS_F1);
				student.setPen1(student.getStudentNumber());
				student.setStudentNumber(null);
			}

			if (penMasterRecord.getMasterStudentSurname() == student.getSurname()
					&& penMasterRecord.getMasterStudentGiven() != student.getGivenName()
					&& penMasterRecord.getMasterStudentDob() == student.getDob()
					&& penMasterRecord.getMasterPenMincode() == student.getMincode()
					&& penMasterRecord.getMasterPenLocalId() != student.getLocalID()
					&& penMasterRecord.getMasterPenLocalId() != null && student.getLocalID() != null) {
				student.setPenStatusMessage("Possible twin: " + penMasterRecord.getMasterStudentGiven().trim() + " vs "
						+ student.getGivenName().trim());
				student.setPenStatus(PEN_STATUS_F1);
				student.setPen1(student.getStudentNumber());
				student.setStudentNumber(null);
			}
		}

		if (student.isDeceased()) {
			student.setPenStatus(PEN_STATUS_C0);
			student.setStudentNumber(null);
		}

		return null;
	}

	private void initialize(PenMatchStudent student) {
		student.setPenStatusMessage(null);
		this.matchingPENs = new String[20];

		this.reallyGoodMatches = 0;
		this.prettyGoodMatches = 0;
		this.reallyGoodPEN = null;

		student.setNumberOfMatches(0);
		this.type5Match = false;
		this.type5F1 = false;
		this.alternateLocalID = "TTT";

		// Strip off leading zeros, leading blanks and trailing blanks
		// from the local_id. Put result in alternateLocalID.
		if (student.getLocalID() != null) {
			alternateLocalID = StringUtils.stripStart(student.getLocalID(), "0");
			alternateLocalID = alternateLocalID.replaceAll(" ", "");
		}

		// Remove blanks from names
		if (student.getSurname() != null) {
			String studentSurnameNoBlanks = student.getSurname().replaceAll(" ", "");
			// Re-calculate Soundex of legal surname
			student.setSoundexSurname(runSoundex(studentSurnameNoBlanks));
		}

		if (student.getUsualSurname() != null) {
			String usualSurnameNoBlanks = student.getUsualSurname().replaceAll(" ", "");
			// Re-calculate Soundex of usual surname
			student.setUsualSoundexSurname(runSoundex(usualSurnameNoBlanks));
		}

		// Store given and middle names from transaction in separate object
		storeNamesFromTransaction(student);

		this.minSurnameSearchSize = 4;
		this.maxSurnameSearchSize = 6;

		if (student.getSurname() != null) {
			this.surnameSize = student.getSurname().length();
		} else {
			this.surnameSize = 0;
		}

		if (this.surnameSize < this.minSurnameSearchSize) {
			this.minSurnameSearchSize = this.surnameSize;
		} else if (this.surnameSize < this.maxSurnameSearchSize) {
			this.maxSurnameSearchSize = this.surnameSize;
		}

		// Lookup surname frequency
		// It could generate extra points later if
		// there is a perfect match on surname
		this.fullSurnameFrequency = 0;
		String fullStudentSurname = student.getSurname();
		this.fullSurnameFrequency = lookupSurnameFrequency();

		if (this.fullSurnameFrequency > VERY_FREQUENT) {
			this.partSurnameFrequency = this.fullSurnameFrequency;
		} else {
			this.partSurnameFrequency = 0;
			fullStudentSurname = student.getSurname().substring(0, this.minSurnameSearchSize);
			this.partSurnameFrequency = lookupSurnameFrequency();
		}
	}

	/**
	 * This function stores all names in an object It includes some split logic for
	 * given/middle names
	 * 
	 * @param student
	 */
	private void storeNamesFromTransaction(PenMatchStudent student) {
		String given = student.getGivenName();
		String usualGiven = student.getUsualGivenName();

		this.penMatchTransactionNames = new PenMatchNames();
		this.penMatchTransactionNames.setLegalGiven(given);
		this.penMatchTransactionNames.setLegalMiddle(student.getMiddleName());
		this.penMatchTransactionNames.setUsualGiven(usualGiven);
		this.penMatchTransactionNames.setUsualMiddle(student.getUsualMiddleName());

		if (given != null) {
			int spaceIndex = StringUtils.indexOf(given, " ");
			if (spaceIndex != -1) {
				this.penMatchTransactionNames.setAlternateLegalGiven(given.substring(0, spaceIndex));
				this.penMatchTransactionNames.setAlternateLegalMiddle(given.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(given, "-");
			if (dashIndex != -1) {
				this.penMatchTransactionNames.setAlternateLegalGiven(given.substring(0, dashIndex));
				this.penMatchTransactionNames.setAlternateLegalMiddle(given.substring(dashIndex));
			}
		}

		if (usualGiven != null) {
			int spaceIndex = StringUtils.indexOf(usualGiven, " ");
			if (spaceIndex != -1) {
				this.penMatchTransactionNames.setAlternateUsualGiven(usualGiven.substring(0, spaceIndex));
				this.penMatchTransactionNames.setAlternateUsualMiddle(usualGiven.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(usualGiven, "-");
			if (dashIndex != -1) {
				this.penMatchTransactionNames.setAlternateUsualGiven(usualGiven.substring(0, dashIndex));
				this.penMatchTransactionNames.setAlternateUsualMiddle(usualGiven.substring(dashIndex));
			}
		}

		lookupNicknames();
	}

	/**
	 * This function stores all names in an object It includes some split logic for
	 * given/middle names
	 * 
	 * @param master
	 */
	private void storeNamesFromMaster(PenMasterRecord master) {
		String given = master.getMasterStudentGiven();
		String usualGiven = master.getMasterUsualGivenName();

		this.penMatchMasterNames = new PenMatchNames();
		this.penMatchMasterNames.setLegalGiven(given);
		this.penMatchMasterNames.setLegalMiddle(master.getMasterStudentMiddle());
		this.penMatchMasterNames.setUsualGiven(usualGiven);
		this.penMatchMasterNames.setUsualMiddle(master.getMasterUsualMiddleName());

		if (given != null) {
			int spaceIndex = StringUtils.indexOf(given, " ");
			if (spaceIndex != -1) {
				this.penMatchMasterNames.setAlternateLegalGiven(given.substring(0, spaceIndex));
				this.penMatchMasterNames.setAlternateLegalMiddle(given.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(given, "-");
			if (dashIndex != -1) {
				this.penMatchMasterNames.setAlternateLegalGiven(given.substring(0, dashIndex));
				this.penMatchMasterNames.setAlternateLegalMiddle(given.substring(dashIndex));
			}
		}

		if (usualGiven != null) {
			int spaceIndex = StringUtils.indexOf(usualGiven, " ");
			if (spaceIndex != -1) {
				this.penMatchMasterNames.setAlternateUsualGiven(usualGiven.substring(0, spaceIndex));
				this.penMatchMasterNames.setAlternateUsualMiddle(usualGiven.substring(spaceIndex));
			}
			int dashIndex = StringUtils.indexOf(usualGiven, "-");
			if (dashIndex != -1) {
				this.penMatchMasterNames.setAlternateUsualGiven(usualGiven.substring(0, dashIndex));
				this.penMatchMasterNames.setAlternateUsualMiddle(usualGiven.substring(dashIndex));
			}
		}

	}

	/**
	 * Example: the original PEN number is 746282656 1. First 8 digits are 74628265
	 * 2. Sum the odd digits: 7 + 6 + 8 + 6 = 27 (S1) 3. Extract the even digits
	 * 4,2,2,5 to get A = 4225. 4. Multiply A times 2 to get B = 8450 5. Sum the
	 * digits of B: 8 + 4 + 5 + 0 = 17 (S2) 6. 27 + 17 = 44 (S3) 7. S3 is not a
	 * multiple of 10 8. Calculate check-digit as 10 - MOD(S3,10): 10 - MOD(44,10) =
	 * 10 - 4 = 6 A) Alternatively, round up S3 to next multiple of 10: 44 becomes
	 * 50 B) Subtract S3 from this: 50 - 44 = 6
	 * 
	 * @param pen
	 * @return
	 */
	private String penCheckDigit(String pen) {
		if (pen == null || pen.length() != 9 || !pen.matches("-?\\d+(\\.\\d+)?")) {
			return CHECK_DIGIT_ERROR_CODE_001;
		}

		ArrayList<Integer> odds = new ArrayList<>();
		ArrayList<Integer> evens = new ArrayList<>();
		for (int i = 0; i < pen.length() - 1; i++) {
			int number = Integer.parseInt(pen.substring(i, i + 1));
			if (i % 2 == 0) {
				odds.add(number);
			} else {
				evens.add(number);
			}
		}

		int sumOdds = odds.stream().mapToInt(Integer::intValue).sum();

		String fullEvenValueString = "";
		for (int i = 0; i < evens.size(); i++) {
			fullEvenValueString += evens.get(i);
		}

		ArrayList<Integer> listOfFullEvenValueDoubled = new ArrayList<>();
		String fullEvenValueDoubledString = Integer.valueOf(Integer.parseInt(fullEvenValueString) * 2).toString();
		for (int i = 0; i < fullEvenValueDoubledString.length(); i++) {
			listOfFullEvenValueDoubled.add(Integer.parseInt(fullEvenValueDoubledString.substring(i, i + 1)));
		}

		int sumEvens = listOfFullEvenValueDoubled.stream().mapToInt(Integer::intValue).sum();

		int finalSum = sumEvens + sumOdds;

		String penCheckDigit = pen.substring(8, 9);

		if ((finalSum % 10 == 0 && penCheckDigit.equals("0"))
				|| ((10 - finalSum % 10) == Integer.parseInt(penCheckDigit))) {
			return CHECK_DIGIT_ERROR_CODE_000;
		} else {
			return CHECK_DIGIT_ERROR_CODE_001;
		}
	}

	private void checkForCoreData(PenMatchStudent student) {
		if (student.getSurname() == null || student.getGivenName() == null || student.getDob() == null
				|| student.getSex() == null || student.getMincode() == null) {
			student.setPenStatus(PEN_STATUS_G0);
		}
	}

	/**
	 * Check for exact match on surname , given name, birthday and gender OR exact
	 * match on school and local ID and one or more of surname, given name or
	 * birthday
	 */
	private void simpleCheckForMatch(PenMatchStudent student, PenMasterRecord master) {
		this.matchFound = false;
		this.type5Match = false;

		if (student.getSurname() != null && student.getSurname() == master.getMasterStudentSurname()
				&& student.getGivenName() != null && student.getGivenName() == master.getMasterStudentGiven()
				&& student.getDob() != null && student.getDob() == master.getMasterStudentDob()
				&& student.getSex() != null && student.getSex() == master.getMasterStudentSex()) {
			this.matchFound = true;
			this.algorithmUsed = PenAlgorithm.ALG_S1;
		} else if (student.getSurname() != null && student.getSurname() == master.getMasterStudentSurname()
				&& student.getGivenName() != null && student.getGivenName() == master.getMasterStudentGiven()
				&& student.getDob() != null && student.getDob() == master.getMasterStudentDob()
				&& student.getLocalID() != null && student.getLocalID().length() > 1) {
			normalizeLocalIDsFromMaster(master);
			if (student.getMincode() != null && student.getMincode() == master.getMasterPenMincode()
					&& (student.getLocalID() == master.getMasterPenLocalId()
							|| this.alternateLocalID == master.getMasterAlternateLocalId())) {
				this.matchFound = true;
				this.algorithmUsed = PenAlgorithm.ALG_S2;
			}
		}

		if (this.matchFound) {
			loadPenMatchHistory();
		}

	}

	/**
	 * Strip off leading zeros , leading blanks and trailing blanks from the
	 * PEN_MASTER stud_local_id. Put result in MAST_PEN_ALT_LOCAL_ID
	 */
	private void normalizeLocalIDsFromMaster(PenMasterRecord master) {
		master.setMasterAlternateLocalId("MMM");
		if (master.getMasterPenLocalId() != null) {
			master.setMasterAlternateLocalId(StringUtils.stripStart(master.getMasterPenLocalId(), "0"));
			master.setMasterAlternateLocalId(master.getMasterAlternateLocalId().replaceAll(" ", ""));
		}
	}

	private PenConfirmationResult confirmPEN(PenMatchStudent student) {
		PenConfirmationResult penConfirmationResult = new PenConfirmationResult();
		String localStudentNumber = student.getStudentNumber();
		student.setDeceased(false);

		PenMasterRecord penMasterRecord = getPENDemogMasterRecord(localStudentNumber);

		if (penMasterRecord != null && penMasterRecord.getMasterStudentNumber() == localStudentNumber) {
			penConfirmationResult.setResultCode(PenConfirmationResult.PEN_ON_FILE);
			if (penMasterRecord.getMasterStudentStatus() != null && penMasterRecord.getMasterStudentStatus().equals("M")
					&& penMasterRecord.getMasterStudentTrueNumber() != null) {
				localStudentNumber = penMasterRecord.getMasterStudentTrueNumber();
				penConfirmationResult.setMergedPEN(penMasterRecord.getMasterStudentTrueNumber());
				penMasterRecord = getPENDemogMasterRecord(localStudentNumber);
				if (penMasterRecord != null && penMasterRecord.getMasterStudentNumber() == localStudentNumber) {
					simpleCheckForMatch(student, penMasterRecord);
					if (penMasterRecord.getMasterStudentStatus().equals("D")) {
						localStudentNumber = null;
						student.setDeceased(true);
					}
				}
			} else {
				simpleCheckForMatch(student, penMasterRecord);
			}
			if (this.matchFound) {
				penConfirmationResult.setResultCode(PenConfirmationResult.PEN_CONFIRMED);
			}
		}

		if (this.matchFound) {
			loadPenMatchHistory();
		}

		penConfirmationResult.setLocalStudentNumber(localStudentNumber);
		return penConfirmationResult;
	}

	/**
	 * Find all possible students on master who could match the transaction - If the
	 * first four characters of surname are uncommon then only use 4 characters in
	 * lookup. Otherwise use 6 characters , or 5 if surname is only 5 characters
	 * long use the given initial in the lookup unless 1st 4 characters of surname
	 * is quite rare
	 */
	private void findMatchesOnPenDemog(PenMatchStudent student, boolean penFoundOnMaster, String localStudentNumber) {
		boolean useGivenInitial = true;
		Integer totalPoints = 0;
		String partStudentSurname;
		String partStudentGiven;

		if (this.partSurnameFrequency <= NOT_VERY_FREQUENT) {
			partStudentSurname = student.getSurname().substring(0, this.minSurnameSearchSize);
			useGivenInitial = false;
		} else {
			if (this.partSurnameFrequency <= VERY_FREQUENT) {
				partStudentSurname = student.getSurname().substring(0, this.minSurnameSearchSize);
				partStudentGiven = student.getGivenName().substring(0, 1);
			} else {
				partStudentSurname = student.getSurname().substring(0, this.maxSurnameSearchSize);
				partStudentGiven = student.getGivenName().substring(0, 2);
			}
		}

		if (student.getLocalID() == null) {
			if (useGivenInitial) {
				totalPoints = lookupNoLocalID();
			} else {
				totalPoints = lookupNoInitNoLocalID();
			}
		} else {
			if (useGivenInitial) {
				totalPoints = lookupWithAllParts();
			} else {
				totalPoints = lookupNoInit();
			}
		}

		// If a PEN was provided, but the demographics didn't match the student
		// on PEN-MASTER with that PEN, then add the student on PEN-MASTER to
		// the list of possible students who match.
		if (student.getPenStatus() == PEN_STATUS_B && penFoundOnMaster) {
			this.reallyGoodMatches = 0;
			this.algorithmUsed = PenAlgorithm.ALG_00;
			this.type5F1 = true;
			mergeNewMatchIntoList(student, localStudentNumber, totalPoints);
		}

		// If only one really good match, and no pretty good matches,
		// just send the one PEN back
		if (student.getPenStatus().substring(0, 1) == PEN_STATUS_D && this.reallyGoodMatches == 1
				&& this.prettyGoodMatches == 0) {
			student.setPen1(this.reallyGoodPEN);
			student.setStudentNumber(this.reallyGoodPEN);
			student.setNumberOfMatches(1);
			student.setPenStatus(PEN_STATUS_D1);
			return;
		} else {
			log.debug("List of matching PENs: {}", matchingPENs);
			student.setPen1(matchingPENs[0]);
			student.setPen2(matchingPENs[1]);
			student.setPen3(matchingPENs[2]);
			student.setPen4(matchingPENs[3]);
			student.setPen5(matchingPENs[4]);
			student.setPen6(matchingPENs[5]);
			student.setPen7(matchingPENs[6]);
			student.setPen8(matchingPENs[7]);
			student.setPen9(matchingPENs[8]);
			student.setPen10(matchingPENs[9]);
			student.setPen11(matchingPENs[10]);
			student.setPen12(matchingPENs[11]);
			student.setPen13(matchingPENs[12]);
			student.setPen14(matchingPENs[13]);
			student.setPen15(matchingPENs[14]);
			student.setPen16(matchingPENs[15]);
			student.setPen17(matchingPENs[16]);
			student.setPen18(matchingPENs[17]);
			student.setPen19(matchingPENs[18]);
			student.setPen20(matchingPENs[19]);
		}

		if (student.getNumberOfMatches() == 0) {
			// No matches found
			student.setPenStatus(student.getPenStatus().trim() + "0");
			student.setStudentNumber(null);
		} else if (student.getNumberOfMatches() == 1) {
			// 1 match only
			if (this.type5F1) {
				student.setPenStatus(PEN_STATUS_F);
				student.setStudentNumber(null);
			} else {
				// one solid match, put in t_stud_no
				student.setStudentNumber(this.matchingPENs[0]);
			}
			student.setPenStatus(student.getPenStatus().trim() + "1");
		} else {
			student.setPenStatus(student.getPenStatus().trim() + "M");
			// many matches, so they are all considered questionable, even if some are
			// "solid"
			student.setStudentNumber(null);
		}

	}

	/**
	 * Calculate points for Sex match
	 */
	private Integer matchSex(PenMatchStudent student, PenMasterRecord master) {
		Integer sexPoints = 0;
		if (student.getSex() != null && student.getSex() == master.getMasterStudentSex()) {
			sexPoints = 5;
		}
		return sexPoints;
	}

	/**
	 * Fetches a PEN Master Record given a student number
	 * 
	 * @param studentNumber
	 * @return
	 */
	private PenMasterRecord getPENDemogMasterRecord(String studentNumber) {
		Optional<PenDemographicsEntity> demog = getPenDemographicsRepository().findByStudNo(studentNumber);
		if (demog.isPresent()) {
			PenDemographicsEntity entity = demog.get();
			PenMasterRecord masterRecord = new PenMasterRecord();
			masterRecord.setMasterStudentNumber(entity.getStudNo());
			masterRecord.setMasterStudentSurname(entity.getStudSurname());
			masterRecord.setMasterStudentGiven(entity.getStudGiven());
			masterRecord.setMasterStudentDob(entity.getStudBirth());
			masterRecord.setMasterStudentSex(entity.getStudSex());
			masterRecord.setMasterStudentGrade(entity.getGrade());
			masterRecord.setMasterPenMincode(entity.getMincode());
			masterRecord.setMasterPenLocalId(entity.getLocalID());
			masterRecord.setMasterStudentStatus(entity.getStudStatus());
			masterRecord.setMasterStudentTrueNumber(entity.getTrueNumber());
			return masterRecord;
		}

		throw new PENMatchRuntimeException("No PEN Demog master record found for student number: " + studentNumber);
	}

	/**
	 * Merge new match into the list Assign points for algorithm and score for sort
	 * use
	 */
	private void mergeNewMatchIntoList(PenMatchStudent student, String wyPEN, Integer totalPoints) {
		Integer wyAlgorithmResult;
		Integer wyScore;
		Integer wyIndex;

		switch (this.algorithmUsed) {
		case ALG_S1:
			wyAlgorithmResult = 100;
			wyScore = 100;
			break;
		case ALG_S2:
			wyAlgorithmResult = 110;
			wyScore = 100;
			break;
		case ALG_SP:
			wyAlgorithmResult = 190;
			wyScore = 100;
			break;
		case ALG_00:
			wyAlgorithmResult = 0;
			wyScore = 1;
			break;
		case ALG_20:
		case ALG_30:
		case ALG_40:
		case ALG_50:
		case ALG_51:
			wyAlgorithmResult = Integer.valueOf(this.algorithmUsed.toString()) * 10;
			wyScore = totalPoints;
			break;
		default:
			log.debug("Unconvertable algorithm code: {}", this.algorithmUsed);
			wyAlgorithmResult = 9999;
			wyScore = 0;
			break;
		}

		// Determine where to insert new item
		wyIndex = student.getNumberOfMatches() + 1;
		// If the array is full
		if (student.getNumberOfMatches() < 20) {
			// Add new slot in the array
			student.setNumberOfMatches(student.getNumberOfMatches() + 1);
		}

		// Move the insertion point up one slot whenever the new item has a lower
		// algorithm point set or a matching algorithm and better score
		while (wyIndex > 1 && (wyAlgorithmResult < this.matchingAlgorithms[wyIndex - 1]
				|| ((wyAlgorithmResult == this.matchingAlgorithms[wyIndex - 1]
						&& wyScore > this.matchingScores[wyIndex - 1])))) {
			wyIndex = wyIndex - 1;
			this.matchingAlgorithms[wyIndex + 1] = this.matchingAlgorithms[wyIndex];
			this.matchingScores[wyIndex + 1] = this.matchingScores[wyIndex];
			this.matchingPENs[wyIndex + 1] = this.matchingPENs[wyIndex];
		}

		// Copy new PEN match to current index
		// Note: if index>20, then the new entry is such a bogus match in
		// terms of algorithm and/or score, that it'll never be sent
		// back to the user anyway, so don't bother copying it into
		// the array.
		if (wyIndex < 21) {
			this.matchingAlgorithms[wyIndex] = wyAlgorithmResult;
			this.matchingScores[wyIndex] = wyScore;
			this.matchingPENs[wyIndex] = wyPEN;
		}

	}

	/**
	 * Soundex calculation
	 * 
	 * @param inputString
	 * @return
	 */
	private String runSoundex(String inputString) {
		String previousCharRaw = null;
		Integer previousCharSoundex = null;
		String currentCharRaw = null;
		Integer currentCharSoundex = null;
		String soundexString = null;
		String tempString = null;

		if (inputString != null && inputString.length() >= 1) {
			tempString = StrUtil.xlate(inputString, inputString, SOUNDEX_CHARACTERS);
			soundexString = inputString.substring(0, 1);
			previousCharRaw = inputString.substring(0, 1);
			previousCharSoundex = -1;

			for (int i = 2; i < tempString.length(); i++) {
				currentCharRaw = inputString.substring(i, i + 1);
				currentCharSoundex = Integer.valueOf(tempString.substring(i, i + 1));

				if (currentCharSoundex >= 1 && currentCharSoundex <= 7) {
					// If the second "soundexable" character is not the same as the first raw
					// character then append the soundex value of this character to the soundex
					// string. If this is the third or greater soundexable value, then if the
					// soundex
					// value of the character is not equal to the soundex value of the previous
					// character, then append that soundex value to the soundex string.
					if (i == 2) {
						if (currentCharRaw != previousCharRaw) {
							soundexString = soundexString + currentCharSoundex;
							previousCharSoundex = currentCharSoundex;
						}
					} else if (currentCharSoundex != previousCharSoundex) {
						soundexString = soundexString + currentCharSoundex;
						previousCharSoundex = currentCharSoundex;
					}
				}
			}

			soundexString = (soundexString + "00000000").substring(0, 8);
		} else {
			soundexString = "10000000";
		}

		return soundexString;
	}

	private Integer checkForMatch(PenMatchStudent student, PenMasterRecord master) {
		this.matchFound = false;
		this.type5Match = false;

		normalizeLocalIDsFromMaster(master);
		storeNamesFromMaster(master);

		Integer totalPoints = 0;
		Integer bonusPoints = 0;
		Integer idDemerits = 0;

		Integer sexPoints = matchSex(student, master); // 5 points
		Integer birthdayPoints = matchBirthday(student, master); // 5, 10, 15 or 20 points
		matchSurname(student, master); // 10 or 20 points
		matchGivenName(student, master); // 5, 10, 15 or 20 points

		// If a perfect match on legal surname , add 5 points if a very rare surname
		if (this.surnamePoints >= 20 && this.fullSurnameFrequency <= VERY_RARE && this.legalUsed) {
			this.surnamePoints = this.surnamePoints + 5;
		}

		MiddleNameMatchResult middleNameMatchResult = matchMiddleName(); // 5, 10, 15 or 20 points

		// If given matches middle and middle matches given and there are some
		// other points, there is a good chance that the names have been flipped
		if (this.givenFlip && middleNameMatchResult.isMiddleNameFlip()
				&& (this.surnamePoints >= 10 || birthdayPoints >= 15)) {
			this.givenNamePoints = 15;
			middleNameMatchResult.setMiddleNamePoints(15);
		}

		LocalIDMatchResult localIDMatchResult = matchLocalID(student, master); // 5, 10 or 20 points
		Integer addressPoints = matchAddress(student, master); // 1 or 10 points

		// Special search algorithm - just looks for any points in all of
		// the non-blank search fields provided
		if (this.updateCode != null && this.updateCode.equals("S")) {
			this.matchFound = true;
			if (student.getSex() != null && sexPoints == 0) {
				this.matchFound = false;
			}
			if (!(student.getSurname() != null && student.getUsualSurname() != null) && this.surnamePoints == 0) {
				this.matchFound = false;
			}
			if (!(student.getGivenName() != null && student.getUsualGivenName() != null) && this.givenNamePoints == 0) {
				this.matchFound = false;
			}
			if (!(student.getMiddleName() != null && student.getUsualMiddleName() != null)
					&& middleNameMatchResult.getMiddleNamePoints() == 0) {
				this.matchFound = false;
			}
			if (student.getDob() != null && birthdayPoints == 0) {
				this.matchFound = false;
			}
			if (!(student.getLocalID() != null && student.getMincode() != null)
					&& localIDMatchResult.getLocalIDPoints() == 0) {
				this.matchFound = false;
			}
			if (student.getPostal() != null && addressPoints == 0) {
				this.matchFound = false;
			}

			if (this.matchFound) {
				this.type5Match = true;
				this.type5F1 = true;
				this.algorithmUsed = PenAlgorithm.ALG_SP;
			}
		}

		// Algorithm 1 : used to be Personal Education No. + 40 bonus points
		// Using SIMPLE_MATCH instead

		// Algorithm 2 : Gender + Birthday + Surname + 25 bonus points (not counting
		// school points and address points so twins are weeded out)
		// Bonus points will include same district or same school + localid ,
		// but not same school
		if (!this.matchFound) {
			if (localIDMatchResult.getLocalIDPoints() == 5 || localIDMatchResult.getLocalIDPoints() == 20) {
				bonusPoints = this.givenNamePoints + middleNameMatchResult.getMiddleNamePoints()
						+ localIDMatchResult.getLocalIDPoints();
			} else {
				bonusPoints = this.givenNamePoints + middleNameMatchResult.getMiddleNamePoints();
			}

			if (sexPoints >= 5 && birthdayPoints >= 20 && this.surnamePoints >= 20) {
				if (bonusPoints >= 25) {
					this.matchFound = true;
					this.reallyGoodMatches = this.reallyGoodMatches + 1;
					this.reallyGoodPEN = master.getMasterStudentNumber();
					totalPoints = sexPoints + birthdayPoints + this.surnamePoints + bonusPoints;
					this.algorithmUsed = PenAlgorithm.ALG_20;
				}
			}
		}

		// Algorithm 3 : School/ local ID + Surname + 25 bonus points
		// (65 points total)
		if (!this.matchFound) {
			if (localIDMatchResult.getLocalIDPoints() >= 20 && this.surnamePoints >= 20) {
				bonusPoints = sexPoints + this.givenNamePoints + middleNameMatchResult.getMiddleNamePoints()
						+ addressPoints;
				if (bonusPoints >= 25) {
					this.matchFound = true;
					this.reallyGoodMatches = this.reallyGoodMatches + 1;
					this.reallyGoodPEN = master.getMasterStudentNumber();
					totalPoints = localIDMatchResult.getLocalIDPoints() + this.surnamePoints + bonusPoints;
					this.algorithmUsed = PenAlgorithm.ALG_30;
				}
			}
		}

		// Algorithm 4: School/local id + gender + birthdate + 20 bonus points
		// (65 points total)
		if (!this.matchFound) {
			if (localIDMatchResult.getLocalIDPoints() >= 20 && sexPoints >= 5 && birthdayPoints >= 20) {
				bonusPoints = this.surnamePoints + this.givenNamePoints + middleNameMatchResult.getMiddleNamePoints()
						+ addressPoints;
				if (bonusPoints >= 20) {
					this.matchFound = true;
					this.reallyGoodMatches = this.reallyGoodMatches + 1;
					this.reallyGoodPEN = master.getMasterStudentNumber();
					totalPoints = localIDMatchResult.getLocalIDPoints() + sexPoints + birthdayPoints + bonusPoints;
					this.algorithmUsed = PenAlgorithm.ALG_40;
				}
			}
		}

		// Algorithm 5: Use points for Sex + birthdate + surname + given name +
		// middle name + address + local_id + school >= 55 bonus points
		if (!this.matchFound) {
			bonusPoints = sexPoints + birthdayPoints + this.surnamePoints + this.givenNamePoints
					+ middleNameMatchResult.getMiddleNamePoints() + localIDMatchResult.getLocalIDPoints()
					+ addressPoints;
			if (bonusPoints >= idDemerits) {
				bonusPoints = bonusPoints - idDemerits;
			} else {
				bonusPoints = 0;
			}

			if (bonusPoints >= 55 || (bonusPoints >= 40 && localIDMatchResult.getLocalIDPoints() >= 20)
					|| (bonusPoints >= 50 && this.surnamePoints >= 10 && birthdayPoints >= 15
							&& this.givenNamePoints >= 15)
					|| (bonusPoints >= 50 && birthdayPoints >= 20)
					|| (bonusPoints >= 50 && student.getLocalID().substring(1, 4).equals("ZZZ"))) {
				this.matchFound = true;
				this.algorithmUsed = PenAlgorithm.ALG_50;
				totalPoints = bonusPoints;
				if (bonusPoints >= 70) {
					this.reallyGoodMatches = this.reallyGoodMatches + 1;
					this.reallyGoodPEN = master.getMasterStudentNumber();
				} else if (bonusPoints >= 60 || localIDMatchResult.getLocalIDPoints() >= 20) {
					this.prettyGoodMatches = this.prettyGoodMatches + 1;
				}
				this.type5F1 = true;
				this.type5Match = true;
			}
		}

		// Algorithm 5.1: Use points for Sex + birthdate + surname + given name +
		// middle name + address + local_id + school >= 55 bonus points
		if (!this.matchFound) {
			if (sexPoints == 5 && birthdayPoints >= 10 && this.surnamePoints >= 20 && this.givenNamePoints >= 10) {
				this.matchFound = true;
				this.algorithmUsed = PenAlgorithm.ALG_51;
				totalPoints = 45;

				// Identify a pretty good match - needs to be better than the Questionable Match
				// but not a full 60 points as above
				if (this.surnamePoints >= 20 && this.givenNamePoints >= 15 && birthdayPoints >= 15 && sexPoints == 5) {
					this.prettyGoodMatches = this.prettyGoodMatches + 1;
					totalPoints = 55;
				}
				this.type5F1 = true;
				this.type5Match = true;
			}
		}

		if (this.matchFound) {
			loadPenMatchHistory();
		}

		return totalPoints;
	}

	/**
	 * Calculate points for Birthday match
	 */
	private Integer matchBirthday(PenMatchStudent student, PenMasterRecord master) {
		Integer birthdayPoints = 0;
		Integer birthdayMatches = 0;
		String dob = student.getDob();

		String masterDob = master.getMasterStudentDob();
		if (dob == null) {
			return null;
		}

		for (int i = 0; i < 8; i++) {
			if (dob.substring(i, i + 1).equals(masterDob.substring(i, i + 1))) {
				birthdayMatches = birthdayMatches + 1;
			}
		}

		// Check for full match
		if (dob.trim().equals(masterDob.trim())) {
			birthdayPoints = 20;
		} else {
			// Same year, month/day flip
			if (dob.substring(0, 4).equals(masterDob.substring(0, 4))
					&& dob.substring(4, 6).equals(masterDob.substring(6, 8))
					&& dob.substring(6, 8).equals(masterDob.substring(4, 6))) {
				birthdayPoints = 15;
			} else {
				// 5 out of 6 right most digits
				if (birthdayMatches >= 5) {
					birthdayPoints = 15;
				} else {
					// Same year and month
					if (dob.substring(0, 6).equals(masterDob.substring(0, 6))) {
						birthdayPoints = 10;
					} else {
						// Same year and day
						if (dob.substring(0, 4).equals(masterDob.substring(0, 4))
								&& dob.substring(6, 8).equals(masterDob.substring(6, 8))) {
							birthdayPoints = 10;
						} else {
							// Same month and day
							if (dob.substring(4, 8).equals(masterDob.substring(4, 8))) {
								birthdayPoints = 5;
							} else {
								// Same year
								if (dob.substring(0, 4).equals(masterDob.substring(0, 4))) {
									birthdayPoints = 5;
								}
							}
						}
					}
				}
			}
		}

		return birthdayPoints;
	}

	/**
	 * Calculate points for address match
	 */
	private Integer matchAddress(PenMatchStudent student, PenMasterRecord master) {
		Integer addressPoints = 0;
		String postal = student.getPostal();
		String masterPostal = master.getMasterPostal();

		if (postal != null && masterPostal != null && postal.equals(masterPostal)
				&& !masterPostal.substring(0, 2).equals("V0")) {
			addressPoints = 10;
		} else if (postal != null && masterPostal != null && postal.equals(masterPostal)
				&& masterPostal.substring(0, 2).equals("V0")) {
			addressPoints = 1;
		}

		return addressPoints;
	}

	/**
	 * Calculate points for Local ID/School code combination - Some schools misuse
	 * the local ID field with a bogus 1 character local ID unfortunately this will
	 * jeopardize the checking for legit 1 character local IDs
	 */
	private LocalIDMatchResult matchLocalID(PenMatchStudent student, PenMasterRecord master) {
		LocalIDMatchResult matchResult = new LocalIDMatchResult();
		Integer localIDPoints = 0;
		String mincode = student.getMincode();
		String localID = student.getLocalID();
		String masterMincode = master.getMasterPenMincode();
		String masterLocalID = master.getMasterPenLocalId();

		if (mincode != null && mincode.equals(masterMincode)
				&& ((localID != null && masterLocalID != null && localID.equals(masterLocalID))
						|| (this.alternateLocalID != null
								&& this.alternateLocalID.equals(master.getMasterAlternateLocalId())))
				&& (localID != null && localID.trim().length() > 1)) {
			localIDPoints = 20;
		}

		// Same School
		if (localIDPoints == 0) {
			if (mincode != null && masterMincode != null && mincode.equals(masterMincode)) {
				localIDPoints = 10;
			}
		}

		// Same district
		if (localIDPoints == 0) {
			if (mincode != null && masterMincode != null
					&& mincode.substring(0, 3).equals(masterMincode.substring(0, 3))
					&& !mincode.substring(0, 3).equals("102")) {
				localIDPoints = 5;
			}
		}

		// Prepare to negate any local_id_points if the local ids actually conflict
		if (localIDPoints > 0 && mincode != null && masterMincode != null && mincode.equals(masterMincode)) {
			if (localID != null && masterLocalID != null && !localID.equals(masterLocalID)
					&& mincode.equals(masterMincode)) {
				if ((this.alternateLocalID != null && master.getMasterAlternateLocalId() != null
						&& !this.alternateLocalID.equals(master.getMasterAlternateLocalId()))
						|| (this.alternateLocalID == null && master.getMasterAlternateLocalId() == null)) {
					matchResult.setIdDemerits(localIDPoints);
				}
			}
		}

		matchResult.setLocalIDPoints(localIDPoints);

		return matchResult;
	}

	/**
	 * Calculate points for surname match
	 */
	private void matchSurname(PenMatchStudent student, PenMasterRecord master) {

	}

	/**
	 * Calculate points for given name match
	 */
	private void matchGivenName(PenMatchStudent student, PenMasterRecord master) {

	}

	/**
	 * Calculate points for middle name match
	 */
	private MiddleNameMatchResult matchMiddleName() {
		Integer middleNamePoints = 0;
		boolean middleFlip = false;

		String legalMiddle = this.penMatchTransactionNames.getLegalMiddle();
		String usualMiddle = this.penMatchTransactionNames.getUsualMiddle();
		String alternateLegalMiddle = this.penMatchTransactionNames.getAlternateLegalMiddle();
		String alternateUsualMiddle = this.penMatchTransactionNames.getAlternateUsualMiddle();

		if ((hasMiddleNameSubsetCharMatch(legalMiddle, 10)) || (hasMiddleNameSubsetCharMatch(usualMiddle, 10))
				|| (hasMiddleNameSubsetCharMatch(alternateLegalMiddle, 10))
				|| (hasMiddleNameSubsetCharMatch(alternateUsualMiddle, 10))) {
			// 10 Character match
			middleNamePoints = 20;
		} else if ((hasMiddleNameSubsetMatch(legalMiddle)) || (hasMiddleNameSubsetMatch(usualMiddle))
				|| (hasMiddleNameSubsetMatch(alternateLegalMiddle))
				|| (hasMiddleNameSubsetMatch(alternateUsualMiddle))) {
			// Has a subset match
			middleNamePoints = 15;
		} else if ((hasMiddleNameSubsetCharMatch(legalMiddle, 4)) || (hasMiddleNameSubsetCharMatch(usualMiddle, 4))
				|| (hasMiddleNameSubsetCharMatch(alternateLegalMiddle, 4))
				|| (hasMiddleNameSubsetCharMatch(alternateUsualMiddle, 4))) {
			// 4 Character Match
			middleNamePoints = 15;
		} else if ((hasMiddleNameSubsetCharMatch(legalMiddle, 1)) || (hasMiddleNameSubsetCharMatch(usualMiddle, 1))
				|| (hasMiddleNameSubsetCharMatch(alternateLegalMiddle, 1))
				|| (hasMiddleNameSubsetCharMatch(alternateUsualMiddle, 1))) {
			// 1 Character Match
			middleNamePoints = 5;
		} else if ((hasMiddleNameSubsetToGivenNameMatch(legalMiddle))
				|| (hasMiddleNameSubsetToGivenNameMatch(usualMiddle))
				|| (hasMiddleNameSubsetToGivenNameMatch(alternateLegalMiddle))
				|| (hasMiddleNameSubsetToGivenNameMatch(alternateUsualMiddle))) {
			// Check middle to given if no matches above (only try 4 characters)
			middleNamePoints = 10;
			middleFlip = true;
		}

		MiddleNameMatchResult result = new MiddleNameMatchResult();
		result.setMiddleNamePoints(middleNamePoints);
		result.setMiddleNameFlip(middleFlip);
		return result;
	}

	/**
	 * Utility function to check for subset middle name matches
	 * 
	 * @param middleName
	 * @return
	 */
	private boolean hasMiddleNameSubsetMatch(String middleName) {
		if (middleName != null && middleName.length() >= 1) {
			if ((this.penMatchMasterNames.getLegalMiddle() != null
					&& (this.penMatchMasterNames.getLegalMiddle().contains(middleName)
							|| middleName.contains(this.penMatchMasterNames.getLegalMiddle())))
					|| (this.penMatchMasterNames.getUsualMiddle() != null
							&& (this.penMatchMasterNames.getUsualMiddle().contains(middleName)
									|| middleName.contains(this.penMatchMasterNames.getUsualMiddle())))
					|| (this.penMatchMasterNames.getAlternateLegalMiddle() != null
							&& (this.penMatchMasterNames.getAlternateLegalMiddle().contains(middleName)
									|| middleName.contains(this.penMatchMasterNames.getAlternateLegalMiddle())))
					|| (this.penMatchMasterNames.getAlternateUsualMiddle() != null
							&& (this.penMatchMasterNames.getAlternateUsualMiddle().contains(middleName)
									|| middleName.contains(this.penMatchMasterNames.getAlternateUsualMiddle())))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Utility function for subset match
	 * 
	 * @param middleName
	 * @param numOfChars
	 * @return
	 */
	private boolean hasMiddleNameSubsetCharMatch(String middleName, int numOfChars) {
		if (middleName != null && middleName.length() >= numOfChars) {
			if ((this.penMatchMasterNames.getLegalMiddle() != null
					&& this.penMatchMasterNames.getLegalMiddle().length() >= numOfChars
					&& this.penMatchMasterNames.getLegalMiddle().substring(0, numOfChars)
							.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getUsualMiddle() != null
							&& this.penMatchMasterNames.getUsualMiddle().length() >= numOfChars
							&& this.penMatchMasterNames.getUsualMiddle().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getAlternateLegalMiddle() != null
							&& this.penMatchMasterNames.getAlternateLegalMiddle().length() >= numOfChars
							&& this.penMatchMasterNames.getAlternateLegalMiddle().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getAlternateUsualMiddle() != null
							&& this.penMatchMasterNames.getAlternateUsualMiddle().length() >= numOfChars
							&& this.penMatchMasterNames.getAlternateUsualMiddle().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Utility function to check for subset middle name matches to given names
	 * 
	 * @param middleName
	 * @return
	 */
	private boolean hasMiddleNameSubsetToGivenNameMatch(String middleName) {
		int numOfChars = 4;
		if (middleName != null && middleName.length() >= numOfChars) {
			if ((this.penMatchMasterNames.getLegalGiven() != null
					&& this.penMatchMasterNames.getLegalGiven().length() >= numOfChars
					&& this.penMatchMasterNames.getLegalGiven().substring(0, numOfChars)
							.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getUsualGiven() != null
							&& this.penMatchMasterNames.getUsualGiven().length() >= numOfChars
							&& this.penMatchMasterNames.getUsualGiven().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getAlternateLegalGiven() != null
							&& this.penMatchMasterNames.getAlternateLegalGiven().length() >= numOfChars
							&& this.penMatchMasterNames.getAlternateLegalGiven().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))
					|| (this.penMatchMasterNames.getAlternateUsualGiven() != null
							&& this.penMatchMasterNames.getAlternateUsualGiven().length() >= numOfChars
							&& this.penMatchMasterNames.getAlternateUsualGiven().substring(0, numOfChars)
									.equals(middleName.substring(0, numOfChars)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Create a log entry for analytical purposes. Not used in our Java
	 * implementation
	 */
	private void loadPenMatchHistory() {
		// Not currently implemented
		// This was a logging function in Basic, we'll likely do something different
	}

	private Integer lookupWithAllParts() {
		// Not currently implemented
		return 0;
	}

	private Integer lookupNoInit() {
		// Not currently implemented
		return 0;
	}

	private Integer lookupNoLocalID() {
		// Not currently implemented
		return 0;
	}

	private Integer lookupNoInitNoLocalID() {
		// Not currently implemented
		return 0;
	}

	private void lookupNicknames() {
		// TODO Implement this
	}

	private Integer lookupSurnameFrequency() {
		// TODO Implement this
		// Note this returns in two different places
		return 0;
	}
}
