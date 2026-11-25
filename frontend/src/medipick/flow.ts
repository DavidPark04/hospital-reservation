import type {Hospital} from '../api';

export const HOME_HOSPITAL_FILTERS = {query:'',region:'',department:''};

export type BookingDraft = {doctorId:string;date:string;slotId?:string};
export type HomeRecommendation = {hospital:Hospital;department?:string};
export type HomeProps = {
  onBooking:(hospital:Hospital,department?:string,draft?:BookingDraft)=>void;
  onAccount:()=>void;
  recommendation?:HomeRecommendation;
  initialScene?:string;
};
