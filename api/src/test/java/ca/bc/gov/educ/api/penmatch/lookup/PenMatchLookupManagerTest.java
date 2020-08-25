package ca.bc.gov.educ.api.penmatch.lookup;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import javax.persistence.EntityManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.bc.gov.educ.api.penmatch.model.NicknamesEntity;
import ca.bc.gov.educ.api.penmatch.model.PenDemographicsEntity;
import ca.bc.gov.educ.api.penmatch.model.SurnameFrequencyEntity;
import ca.bc.gov.educ.api.penmatch.repository.NicknamesRepository;
import ca.bc.gov.educ.api.penmatch.repository.PenDemographicsRepository;
import ca.bc.gov.educ.api.penmatch.repository.SurnameFrequencyRepository;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMasterRecord;
import ca.bc.gov.educ.api.penmatch.struct.v1.PenMatchNames;

@RunWith(SpringRunner.class)
@SpringBootTest
@ActiveProfiles("test")
public class PenMatchLookupManagerTest {

	@Autowired
	NicknamesRepository nicknamesRepository;

	@Autowired
	PenDemographicsRepository penDemographicsRepository;

	@Autowired
	SurnameFrequencyRepository surnameFrequencyRepository;

	@Autowired
	private EntityManager entityManager;

	private static PenMatchLookupManager lookupManager;

	private static boolean dataLoaded = false;

	@Before
	public void before() throws Exception {
		if (!dataLoaded) {
			final File file = new File("src/test/resources/mock_pen_demog.json");
			List<PenDemographicsEntity> penDemogEntities = new ObjectMapper().readValue(file, new TypeReference<List<PenDemographicsEntity>>() {
			});
			penDemographicsRepository.saveAll(penDemogEntities);

			final File fileNick = new File("src/test/resources/mock_nicknames.json");
			List<NicknamesEntity> nicknameEntities = new ObjectMapper().readValue(fileNick, new TypeReference<List<NicknamesEntity>>() {
			});
			nicknamesRepository.saveAll(nicknameEntities);

			final File fileSurnameFreqs = new File("src/test/resources/mock_surname_frequency.json");
			List<SurnameFrequencyEntity> surnameFreqEntities = new ObjectMapper().readValue(fileSurnameFreqs, new TypeReference<List<SurnameFrequencyEntity>>() {
			});
			surnameFrequencyRepository.saveAll(surnameFreqEntities);
			lookupManager = new PenMatchLookupManager(entityManager, penDemographicsRepository, nicknamesRepository, surnameFrequencyRepository);
			dataLoaded = true;
		}
	}

	@Test
	public void testLookupSurnameFrequency_ShouldReturn0() {
		assertTrue(lookupManager.lookupSurnameFrequency("ASDFJSD") == 0);
	}

	@Test
	public void testLookupSurnameFrequency_ShouldReturnOver200() {
		assertTrue(lookupManager.lookupSurnameFrequency("JAM") > 200);
	}

	@Test
	public void testLookupNicknames_ShouldReturn4Names() {
		PenMatchNames penMatchTransactionNames = new PenMatchNames();

		lookupManager.lookupNicknames(penMatchTransactionNames, "JAMES");

		assertNotNull(penMatchTransactionNames.getNickname1());
		assertNotNull(penMatchTransactionNames.getNickname2());
		assertNotNull(penMatchTransactionNames.getNickname3());
		assertNotNull(penMatchTransactionNames.getNickname4());
	}

	@Test
	public void testLookupStudentByPEN() {
		PenMasterRecord masterRecord = lookupManager.lookupStudentByPEN("108999400");

		assertNotNull(masterRecord);
	}

	@Test
	public void testLookupStudentWithAllParts() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupWithAllParts("19981102", "ODLUS", "VICTORIA", "00501007", "239661");

		assertNotNull(penDemogRecords);
		assertTrue(penDemogRecords.size() > 0);
	}

	@Test
	public void testLookupStudentNoInitLargeData() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupNoInit("19981102", "ODLUS", "VICTORIA", "00501007");

		assertNotNull(penDemogRecords);
		assertTrue(penDemogRecords.size() > 0);
	}

	@Test
	public void testLookupStudentNoInit() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupNoInit("19791018", "VANDERLEEK", "JAKE", "08288006");

		assertNotNull(penDemogRecords);
		assertTrue(penDemogRecords.size() > 0);
	}

	@Test
	public void testLookupStudentNoLocalIDLargeData() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupNoLocalID("19981102", "ODLUS", "VICTORIA");

		assertNotNull(penDemogRecords);
		assertTrue(penDemogRecords.size() > 0);
	}

	@Test
	public void testLookupStudentNoLocalID() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupNoLocalID("19791018", "VANDERLEEK", "JAKE");

		assertNotNull(penDemogRecords);
		assertTrue(penDemogRecords.size() > 0);
	}

	@Test
	public void testLookupStudentNoInitNoLocalID() {
		List<PenDemographicsEntity> penDemogRecords = lookupManager.lookupNoInitNoLocalID("19791018", "VANDERLEEK");

		assertNotNull(penDemogRecords);
	}

}
